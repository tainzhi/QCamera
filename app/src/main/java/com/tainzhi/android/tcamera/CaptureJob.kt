package com.tainzhi.android.tcamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import com.tainzhi.android.tcamera.MainActivity.Companion.CameraMode
import com.tainzhi.android.tcamera.util.Kpi
import com.tainzhi.android.tcamera.util.SettingsManager
import kotlinx.coroutines.Runnable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * @author:       tainzhi
 * @mail:         qfq61@qq.com
 * @date:         2019/11/27 下午7:52
 * @description:
 **/
enum class CaptureType {
    UNKNOWN,
    JPEG,
    HDR,
    VIDEO;

    companion object {
        private val map = CameraMode.values().associateBy { it.ordinal }
        fun fromInt(value: Int): CameraMode = map[value]!!
    }
}

class CaptureJobManager(val context: Context, val onThumbnailBitmapUpdate: (bitmap: Bitmap, captureType: CaptureType) -> Unit) {
    init {
        ImageProcessor.captureJobManager = this
    }
    private val thread = HandlerThread("CaptureJobManagerThread").apply { start() }
    private val handler = Handler(thread.looper) { msg ->
        when (msg.what) {

        }
        true
    }
    private val jobMap = mutableMapOf<Int, CaptureJob>()
    private var currentJobId = -1

    fun addJob(captureJob: CaptureJob) {
        Log.d(TAG, "addJob: job-${captureJob.id}")
        jobMap[captureJob.id] = captureJob
        currentJobId = captureJob.id
    }

    private fun getCurrentJob(): CaptureJob? {
        if (currentJobId == -1 || jobMap.isEmpty() || !jobMap.containsKey(currentJobId)) {
            return null
        }
        return jobMap[currentJobId]
    }

    fun removeJob(jobId: Int) {
        Log.d(TAG, "removeJob: job-$jobId")
        jobMap.remove(jobId)
    }

    fun processJpegImage(filterTypeTag: Int, image: Image) {
        handler.post(Runnable {
            assert(image.format == ImageFormat.JPEG)
            if (filterTypeTag == 0) {
                jobMap[currentJobId]!!.jpegImage = image
                Log.i(TAG, "processJpegImage: not apply filter")
                saveJpeg(jobMap[currentJobId]!!)
            } else {
                Log.i(TAG, "processJpegImage: to apply filter:${filterTypeTag}")
                jobMap[currentJobId]!!.jpegImage = null
                ImageProcessor.instance.applyFilterEffectToJpeg(currentJobId, filterTypeTag, image)
                image.close()
            }
        })
    }

    fun processYuvImage(filterTag: Int, image: Image?) {
        handler.post({
            if (image != null) {
                Log.d(TAG, "begin processYuvImage: ")
                ImageProcessor.instance.collectImage(currentJobId, filterTag, image)
                image.close()
                Log.d(TAG, "end processYuvImage: image close")
            } else {
                Log.d(TAG, "processYuvImage but image is null, so abort capture job-${currentJobId}")
                ImageProcessor.instance.abortCapture(currentJobId)
            }
        })
    }

    fun onNativeProcessed(jobId: Int, type: Int, processedImagePath: String) {
        if (App.DEBUG) {
            Log.d(TAG, "onNativeProcessed: job-${jobId}, type:$type")
        }
        // hdr capture
        if (type == 0 || type == 2 /* apply filter effect to hdr jpeg*/) {
            handler.post {
                replaceJpegImage(jobId, processedImagePath)
            }
        } else if (type == 1) {  // apply filter effect to jpeg
            val job = jobMap[jobId] ?: return
            job.cachedJpegPath = processedImagePath
            saveJpeg(job)
        }
    }

    /**
     * HDR: 多帧拍经过 native 处理生成的图片要替换到普通拍照的 JPEG
     */
    private fun replaceJpegImage(jobId: Int, processImagePath: String) {
        Log.d(TAG, "replaceJpegImage: jobMap has Job:${jobMap.containsKey(jobId)}, AppDebug:${App.DEBUG}")
        val job = jobMap[jobId] ?: return
        if (App.DEBUG) {
            Log.d(TAG, "replaceJpegImage: job-${job.id} ${job.captureType}")
        }
        Kpi.start(Kpi.TYPE.PROCESSED_IMAGE_TO_REPLACE_JPEG_IMAGE)
        try {
            val processedImageFile = File(processImagePath)
            val processedImageBytes = FileInputStream(processedImageFile).use {
                it.readBytes()
            }
            val resolver = context.contentResolver
            job.uri?.let { uri ->
                val stream = resolver.openOutputStream(uri)
                if (stream != null) {
                    stream.write(processedImageBytes)
                    stream.close()
                } else {
                    throw IOException("Failed to create new MediaStore record")
                }
            }
        } catch (e: Exception) {
            throw (e)
        }
        Kpi.end(Kpi.TYPE.PROCESSED_IMAGE_TO_REPLACE_JPEG_IMAGE)
    }

    fun processVideo() {
        Log.d(TAG, "processVideo: ")
        handler.post({
            val currentJob = jobMap[currentJobId]
            context.contentResolver.query(currentJob!!.uri!!, null, null, null, null)?.run {
                if (moveToFirst()) {
                    val filePath = getString(getColumnIndexOrThrow(MediaStore.Video.Media.DATA))
                    Log.d(TAG, "processVideo: $filePath")
                    generateThumbnail(currentJobId)
                }
            }
        })
    }

    private fun generateThumbnail(jobId: Int) {
        Log.d(TAG, "generateThumbnail: job-${jobId}")
        val job = jobMap[jobId]!!
        Kpi.start(Kpi.TYPE.IMAGE_TO_THUMBNAIL)
        val thumbnail = if (Build.VERSION.SDK_INT < VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val temp = MediaStore.Images.Media.getBitmap(context.contentResolver, job.uri!!)
            ThumbnailUtils.extractThumbnail(temp, 360, 360)
        } else {
            context.contentResolver.loadThumbnail(job.uri!!, Size(360, 360), null)
        }
        Kpi.end(Kpi.TYPE.IMAGE_TO_THUMBNAIL)
        onThumbnailBitmapUpdate(thumbnail, job.captureType)
        SettingsManager.instance.apply {
            saveLastCaptureMediaType(job.captureType)
            saveLastCaptureMediaUri(job.uri!!)
        }
        if (jobMap[jobId]!!.captureType == CaptureType.JPEG) {
            removeJob(jobId)
        }
    }

    /**
     *  saveJpeg -> generateThumbnail
     */
    private fun saveJpeg(job: CaptureJob) {
        Log.d(TAG, "saveJpeg: job-${job.id} ${job.captureType}")
        Kpi.start(Kpi.TYPE.SHOT_TO_SAVE_IMAGE)
        val resolver = context.contentResolver
        val bytes = if(job.jpegImage != null) {
            val image: Image = job.jpegImage!!
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(image.planes[0].buffer.remaining())
            buffer.get(bytes)
            bytes
        } else {
            val processedImageFile = File(job.cachedJpegPath)
            FileInputStream(processedImageFile).use {
                it.readBytes()
            }
        }

        try {
            job.uri?.let { uri ->
                val stream = resolver.openOutputStream(uri)
                if (stream != null) {
                    stream.write(bytes)
                    stream.close()
                    Log.d(TAG, "save jpeg to ${job.uri}")
                    generateThumbnail(job.id)
                } else {
                    throw IOException("Failed to create new MediaStore record")
                }
            }
        } catch (e: IOException) {
            job.uri?.let { resolver.delete(it, null, null) }
            throw IOException(e)
        } finally {
            // 必须关掉, 否则不能连续拍照
            job.jpegImage?.close()
        }
        Kpi.end(Kpi.TYPE.SHOT_TO_SAVE_IMAGE)
    }

    companion object {
        private val TAG = CaptureJob::class.java.simpleName
    }
}

class CaptureJob {
    private val context: Context
    private val captureJobManager: CaptureJobManager
    private val captureTime: Long
    val captureType: CaptureType
    val id = SettingsManager.instance.getJobId() + 1
    val uri by lazy { getMediaUri() }
    var jpegImage: Image? = null
    var cachedJpegPath: String? = null
    private lateinit var exposureTimes: List<Long>
    private var yuvImageSize = 0

    constructor(context: Context,
                captureJobManager: CaptureJobManager,
                captureTime: Long,
                captureType: CaptureType) {
        this.context = context
        this.captureJobManager = captureJobManager
        this.captureTime = captureTime
        this.captureType = captureType
        SettingsManager.instance.saveJobId(id)
        if (captureType == CaptureType.HDR) yuvImageSize = MainActivity.CAPTURE_HDR_FRAME_SIZE
        captureJobManager.addJob(this)

    }

    constructor(
        context: Context,
        captureJobManager: CaptureJobManager,
        captureTime: Long,
        captureType: CaptureType,
        orientation: Int,
        exposureTimes: List<Long>
    ) : this(context, captureJobManager, captureTime, captureType) {
        this.exposureTimes = exposureTimes
        if (captureType == CaptureType.HDR) {
            yuvImageSize = MainActivity.CAPTURE_HDR_FRAME_SIZE
            ImageProcessor.instance.capture(id, captureType.ordinal,"${SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US).format(captureTime)}", orientation,  yuvImageSize, exposureTimes)
        }
    }

    private fun getMediaUri(): Uri? {
        val relativeLocation = Environment.DIRECTORY_DCIM + "/Camera"
        lateinit var fileName: String
        var mediaUri: Uri?
        val contentValues = ContentValues().apply {
            var fileExtension: String
            var filePrefix: String
            when(captureType) {
                CaptureType.JPEG -> {
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpg")
                    fileExtension = ".jpeg"
                    filePrefix = "IMG_"
                }
                CaptureType.HDR -> {
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpg")
                    fileExtension = "_HDR.jpeg"
                    filePrefix = "IMG_"
                }
                CaptureType.VIDEO -> {
                    fileExtension = ".mp4"
                    filePrefix = "VID_"
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                }

                CaptureType.UNKNOWN -> TODO()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeLocation)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            fileName = "${filePrefix}${SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US).format(captureTime)}${fileExtension}"
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        }
        mediaUri = when(captureType) {
            CaptureType.JPEG, CaptureType.HDR -> {
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            }

            CaptureType.VIDEO -> {
                context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            }

            CaptureType.UNKNOWN -> TODO()
        }
        if (App.DEBUG) {
            Log.d(TAG, "getMediaUri: $fileName")
        }
        return mediaUri
    }

    companion object {
        private val TAG = CaptureJob.javaClass.simpleName
    }
}