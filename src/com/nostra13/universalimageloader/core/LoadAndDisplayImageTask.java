/*******************************************************************************
 * Copyright 2011-2013 Sergey Tarasevich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.nostra13.universalimageloader.core;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.Reference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import android.graphics.Bitmap;
import android.os.Handler;
import android.widget.ImageView;

import com.nostra13.universalimageloader.cache.disc.DiscCacheAware;
import com.nostra13.universalimageloader.core.assist.FailReason;
import com.nostra13.universalimageloader.core.assist.FailReason.FailType;
import com.nostra13.universalimageloader.core.assist.ImageLoadingListener;
import com.nostra13.universalimageloader.core.assist.ImageScaleType;
import com.nostra13.universalimageloader.core.assist.ImageSize;
import com.nostra13.universalimageloader.core.assist.LoadedFrom;
import com.nostra13.universalimageloader.core.assist.ViewScaleType;
import com.nostra13.universalimageloader.core.decode.ImageDecoder;
import com.nostra13.universalimageloader.core.decode.ImageDecodingInfo;
import com.nostra13.universalimageloader.core.download.ImageDownloader;
import com.nostra13.universalimageloader.core.download.ImageDownloader.Scheme;
import com.nostra13.universalimageloader.utils.IoUtils;
import com.nostra13.universalimageloader.utils.L;

/**
 * Presents load'n'display image task. Used to load image from Internet or file
 * system, decode it to {@link Bitmap}, and display it in {@link ImageView}
 * using {@link DisplayBitmapTask}.
 * 
 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
 * @see ImageLoaderConfiguration
 * @see ImageLoadingInfo
 * @since 1.3.1
 */
final class LoadAndDisplayImageTask implements Runnable {

	private static final String LOG_WAITING_FOR_RESUME = "ImageLoader is paused. Waiting...  [%s]";
	private static final String LOG_RESUME_AFTER_PAUSE = ".. Resume loading [%s]";
	private static final String LOG_DELAY_BEFORE_LOADING = "Delay %d ms before loading...  [%s]";
	private static final String LOG_START_DISPLAY_IMAGE_TASK = "Start display image task [%s]";
	private static final String LOG_WAITING_FOR_IMAGE_LOADED = "Image already is loading. Waiting... [%s]";
	private static final String LOG_GET_IMAGE_FROM_MEMORY_CACHE_AFTER_WAITING = "...Get cached bitmap from memory after waiting. [%s]";
	private static final String LOG_LOAD_IMAGE_FROM_NETWORK = "Load image from network [%s]";
	private static final String LOG_LOAD_IMAGE_FROM_DISC_CACHE = "Load image from disc cache [%s]";
	private static final String LOG_PREPROCESS_IMAGE = "PreProcess image before caching in memory [%s]";
	private static final String LOG_POSTPROCESS_IMAGE = "PostProcess image before displaying [%s]";
	private static final String LOG_CACHE_IMAGE_IN_MEMORY = "Cache image in memory [%s]";
	private static final String LOG_CACHE_IMAGE_ON_DISC = "Cache image on disc [%s]";
	private static final String LOG_PROCESS_IMAGE_BEFORE_CACHE_ON_DISC = "Process image before cache on disc [%s]";
	private static final String LOG_TASK_CANCELLED_IMAGEVIEW_REUSED = "ImageView is reused for another image. Task is cancelled. [%s]";
	private static final String LOG_TASK_CANCELLED_IMAGEVIEW_LOST = "ImageView was collected by GC. Task is cancelled. [%s]";
	private static final String LOG_TASK_INTERRUPTED = "Task was interrupted [%s]";

	private static final String ERROR_PRE_PROCESSOR_NULL = "Pre-processor returned null [%s]";
	private static final String ERROR_POST_PROCESSOR_NULL = "Pre-processor returned null [%s]";
	private static final String ERROR_PROCESSOR_FOR_DISC_CACHE_NULL = "Bitmap processor for disc cache returned null [%s]";

	private static final int BUFFER_SIZE = IoUtils.BUFFER_SIZE;

	private final ImageLoaderEngine engine;
	private final ImageLoadingInfo imageLoadingInfo;
	private final Handler handler;

	// Helper references
	private final ImageLoaderConfiguration configuration;
	private final ImageDownloader downloader;
	private final ImageDownloader networkDeniedDownloader;
	private final ImageDownloader slowNetworkDownloader;
	private final ImageDecoder decoder;
	private final boolean writeLogs;
	final String uri;
	private final String memoryCacheKey;
	final Reference<ImageView> imageViewRef;
	private final ImageSize targetSize;
	final DisplayImageOptions options;
	final ImageLoadingListener listener;

	// State vars
	private LoadedFrom loadedFrom = LoadedFrom.NETWORK;
	private boolean imageViewCollected = false;

	public LoadAndDisplayImageTask(final ImageLoaderEngine engine, final ImageLoadingInfo imageLoadingInfo,
			final Handler handler) {
		this.engine = engine;
		this.imageLoadingInfo = imageLoadingInfo;
		this.handler = handler;

		configuration = engine.configuration;
		downloader = configuration.downloader;
		networkDeniedDownloader = configuration.networkDeniedDownloader;
		slowNetworkDownloader = configuration.slowNetworkDownloader;
		decoder = configuration.decoder;
		writeLogs = configuration.writeLogs;
		uri = imageLoadingInfo.uri;
		memoryCacheKey = imageLoadingInfo.memoryCacheKey;
		imageViewRef = imageLoadingInfo.imageViewRef;
		targetSize = imageLoadingInfo.targetSize;
		options = imageLoadingInfo.options;
		listener = imageLoadingInfo.listener;
	}

	@Override
	public void run() {
		if (waitIfPaused()) return;
		if (delayIfNeed()) return;

		final ReentrantLock loadFromUriLock = imageLoadingInfo.loadFromUriLock;
		log(LOG_START_DISPLAY_IMAGE_TASK);
		if (loadFromUriLock.isLocked()) {
			log(LOG_WAITING_FOR_IMAGE_LOADED);
		}

		loadFromUriLock.lock();
		Bitmap bmp;
		try {
			if (checkTaskIsNotActual()) return;

			bmp = configuration.memoryCache.get(memoryCacheKey);
			if (bmp == null) {
				bmp = tryLoadBitmap();
				if (imageViewCollected) return; // listener callback already was
												// fired
				if (bmp == null) return; // listener callback already was fired

				if (checkTaskIsNotActual() || checkTaskIsInterrupted()) return;

				if (options.shouldPreProcess()) {
					log(LOG_PREPROCESS_IMAGE);
					bmp = options.getPreProcessor().process(bmp);
					if (bmp == null) {
						L.e(ERROR_PRE_PROCESSOR_NULL);
					}
				}

				if (bmp != null && options.isCacheInMemory()) {
					log(LOG_CACHE_IMAGE_IN_MEMORY);
					configuration.memoryCache.put(memoryCacheKey, bmp);
				}
			} else {
				loadedFrom = LoadedFrom.MEMORY_CACHE;
				log(LOG_GET_IMAGE_FROM_MEMORY_CACHE_AFTER_WAITING);
			}

			if (bmp != null && options.shouldPostProcess()) {
				log(LOG_POSTPROCESS_IMAGE);
				bmp = options.getPostProcessor().process(bmp);
				if (bmp == null) {
					L.e(ERROR_POST_PROCESSOR_NULL, memoryCacheKey);
				}
			}
		} finally {
			loadFromUriLock.unlock();
		}

		if (checkTaskIsNotActual() || checkTaskIsInterrupted()) return;

		final DisplayBitmapTask displayBitmapTask = new DisplayBitmapTask(bmp, imageLoadingInfo, engine, loadedFrom);
		displayBitmapTask.setLoggingEnabled(writeLogs);
		handler.post(displayBitmapTask);
	}

	private ImageView checkImageViewRef() {
		final ImageView imageView = imageViewRef.get();
		if (imageView == null) {
			imageViewCollected = true;
			log(LOG_TASK_CANCELLED_IMAGEVIEW_LOST);
			fireCancelEvent();
		}
		return imageView;
	}

	private boolean checkImageViewReused(final ImageView imageView) {
		final String currentCacheKey = engine.getLoadingUriForView(imageView);
		// Check whether memory cache key (image URI) for current ImageView is
		// actual.
		// If ImageView is reused for another task then current task should be
		// cancelled.
		final boolean imageViewWasReused = !memoryCacheKey.equals(currentCacheKey);
		if (imageViewWasReused) {
			log(LOG_TASK_CANCELLED_IMAGEVIEW_REUSED);
			fireCancelEvent();
		}
		return imageViewWasReused;
	}

	private boolean checkTaskIsImageViewReused() {
		final ImageView imageView = checkImageViewRef();
		if (imageView == null) return false;
		final String currentCacheKey = engine.getLoadingUriForView(imageView);
		return !memoryCacheKey.equals(currentCacheKey);
	}

	/** Check whether the current task was interrupted */
	private boolean checkTaskIsInterrupted() {
		final boolean interrupted = Thread.interrupted();
		if (interrupted) {
			log(LOG_TASK_INTERRUPTED);
		}
		return interrupted;
	}

	/**
	 * Check whether target ImageView wasn't collected by GC and the image URI
	 * of this task matches to image URI which is actual for current ImageView
	 * at this moment and fire
	 * {@link ImageLoadingListener#onLoadingCancelled(String, android.view.View)}
	 * event if it doesn't.
	 */
	private boolean checkTaskIsNotActual() {
		final ImageView imageView = checkImageViewRef();
		return imageView == null || checkImageViewReused(imageView);
	}

	private Bitmap decodeImage(final String imageUri) throws IOException {
		final ImageView imageView = checkImageViewRef();
		if (imageView == null) return null;

		final ViewScaleType viewScaleType = ViewScaleType.fromImageView(imageView);
		final ImageDecodingInfo decodingInfo = new ImageDecodingInfo(memoryCacheKey, imageUri, targetSize,
				viewScaleType, getDownloader(), options);
		return decoder.decode(decodingInfo);
	}

	/** @return true - if task should be interrupted; false - otherwise */
	private boolean delayIfNeed() {
		if (options.shouldDelayBeforeLoading()) {
			log(LOG_DELAY_BEFORE_LOADING, options.getDelayBeforeLoading(), memoryCacheKey);
			try {
				Thread.sleep(options.getDelayBeforeLoading());
			} catch (final InterruptedException e) {
				L.e(LOG_TASK_INTERRUPTED, memoryCacheKey);
				return true;
			}
			return checkTaskIsNotActual();
		}
		return false;
	}

	private void downloadImage(final File targetFile) throws IOException {
		final InputStream is = getDownloader().getStream(uri, options.getExtraForDownloader());
		try {
			final OutputStream os = new BufferedOutputStream(new FileOutputStream(targetFile), BUFFER_SIZE);
			try {
				IoUtils.copyStream(is, os, new StreamCopyListenerImpl(this));
			} finally {
				IoUtils.closeSilently(os);
			}
		} finally {
			IoUtils.closeSilently(is);
		}
	}

	private boolean downloadSizedImage(final File targetFile, final int maxWidth, final int maxHeight)
			throws IOException {
		// Download, decode, compress and save image
		final ImageSize targetImageSize = new ImageSize(maxWidth, maxHeight);
		final DisplayImageOptions specialOptions = new DisplayImageOptions.Builder().cloneFrom(options)
				.imageScaleType(ImageScaleType.IN_SAMPLE_INT).build();
		final ImageDecodingInfo decodingInfo = new ImageDecodingInfo(memoryCacheKey, uri, targetImageSize,
				ViewScaleType.FIT_INSIDE, getDownloader(), specialOptions);
		Bitmap bmp = decoder.decode(decodingInfo);
		if (bmp == null) return false;

		if (configuration.processorForDiscCache != null) {
			log(LOG_PROCESS_IMAGE_BEFORE_CACHE_ON_DISC);
			bmp = configuration.processorForDiscCache.process(bmp);
			if (bmp == null) {
				L.e(ERROR_PROCESSOR_FOR_DISC_CACHE_NULL, memoryCacheKey);
				return false;
			}
		}

		final OutputStream os = new BufferedOutputStream(new FileOutputStream(targetFile), BUFFER_SIZE);
		boolean savedSuccessfully;
		try {
			savedSuccessfully = bmp.compress(configuration.imageCompressFormatForDiscCache,
					configuration.imageQualityForDiscCache, os);
		} finally {
			IoUtils.closeSilently(os);
		}
		bmp.recycle();
		return savedSuccessfully;
	}

	private void fireCancelEvent() {
		if (!Thread.interrupted()) {
			handler.post(new Runnable() {
				@Override
				public void run() {
					listener.onLoadingCancelled(uri, imageViewRef.get());
				}
			});
		}
	}

	private void fireFailEvent(final FailType failType, final Throwable failCause) {
		if (!Thread.interrupted()) {
			handler.post(new Runnable() {
				@Override
				public void run() {
					final ImageView imageView = imageViewRef.get();
					if (imageView != null && options.shouldShowImageOnFail()) {
						imageView.setImageResource(options.getImageOnFail());
					}
					listener.onLoadingFailed(uri, imageView, new FailReason(failType, failCause));
				}
			});
		}
	}

	private ImageDownloader getDownloader() {
		ImageDownloader d;
		if (engine.isNetworkDenied()) {
			d = networkDeniedDownloader;
		} else if (engine.isSlowNetwork()) {
			d = slowNetworkDownloader;
		} else {
			d = downloader;
		}
		return d;
	}

	private File getImageFileInDiscCache() {
		final DiscCacheAware discCache = configuration.discCache;
		File imageFile = discCache.get(uri);
		File cacheDir = imageFile.getParentFile();
		if (cacheDir == null || !cacheDir.exists() && !cacheDir.mkdirs()) {
			imageFile = configuration.reserveDiscCache.get(uri);
			cacheDir = imageFile.getParentFile();
			if (cacheDir != null && !cacheDir.exists()) {
				cacheDir.mkdirs();
			}
		}
		return imageFile;
	}

	private void log(final String message) {
		if (writeLogs) {
			L.d(message, memoryCacheKey);
		}
	}

	private void log(final String message, final Object... args) {
		if (writeLogs) {
			L.d(message, args);
		}
	}

	/** @return Cached image URI; or original image URI if caching failed */
	private String tryCacheImageOnDisc(final File targetFile) {
		log(LOG_CACHE_IMAGE_ON_DISC);

		try {
			final int width = configuration.maxImageWidthForDiscCache;
			final int height = configuration.maxImageHeightForDiscCache;
			boolean saved = false;
			if (width > 0 || height > 0) {
				saved = downloadSizedImage(targetFile, width, height);
			}
			if (!saved) {
				downloadImage(targetFile);
			}

			configuration.discCache.put(uri, targetFile);
			return Scheme.FILE.wrap(targetFile.getAbsolutePath());
		} catch (final IOException e) {
			L.e(e);
			return uri;
		}
	}

	private Bitmap tryLoadBitmap() {
		final File imageFile = getImageFileInDiscCache();

		Bitmap bitmap = null;
		try {
			if (imageFile.exists()) {
				log(LOG_LOAD_IMAGE_FROM_DISC_CACHE);

				loadedFrom = LoadedFrom.DISC_CACHE;
				bitmap = decodeImage(Scheme.FILE.wrap(imageFile.getAbsolutePath()));
				if (imageViewCollected) return null;
			}
			if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
				log(LOG_LOAD_IMAGE_FROM_NETWORK);

				loadedFrom = LoadedFrom.NETWORK;
				final String imageUriForDecoding = options.isCacheOnDisc() ? tryCacheImageOnDisc(imageFile) : uri;
				if (!checkTaskIsNotActual()) {
					bitmap = decodeImage(imageUriForDecoding);
					if (imageViewCollected) return null;
					if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
						fireFailEvent(FailType.DECODING_ERROR, null);
					}
				}
			}
		} catch (final IllegalStateException e) {
			fireFailEvent(FailType.NETWORK_DENIED, null);
		} catch (final IOException e) {
			L.e(e);
			fireFailEvent(FailType.IO_ERROR, e);
			if (imageFile.exists()) {
				imageFile.delete();
			}
		} catch (final OutOfMemoryError e) {
			L.e(e);
			fireFailEvent(FailType.OUT_OF_MEMORY, e);
		} catch (final Throwable e) {
			L.e(e);
			fireFailEvent(FailType.UNKNOWN, e);
		}
		return bitmap;
	}

	/** @return true - if task should be interrupted; false - otherwise */
	private boolean waitIfPaused() {
		final AtomicBoolean pause = engine.getPause();
		synchronized (pause) {
			if (pause.get()) {
				log(LOG_WAITING_FOR_RESUME);
				try {
					pause.wait();
				} catch (final InterruptedException e) {
					L.e(LOG_TASK_INTERRUPTED, memoryCacheKey);
					return true;
				}
				log(LOG_RESUME_AFTER_PAUSE);
			}
		}
		return checkTaskIsNotActual();
	}

	String getLoadingUri() {
		return uri;
	}

	private static class StreamCopyListenerImpl implements IoUtils.StreamCopyListener {

		private final LoadAndDisplayImageTask task;
		private final Handler handler;
		private final ImageLoadingListener listener;
		private final String uri;
		private final Reference<ImageView> imageViewRef;

		StreamCopyListenerImpl(final LoadAndDisplayImageTask task) {
			this.task = task;
			handler = task.handler;
			listener = task.listener;
			uri = task.uri;
			imageViewRef = task.imageViewRef;
		}

		@Override
		public void onUpdate(final int current, final int total) {
			if (task.checkTaskIsImageViewReused() || task.checkTaskIsInterrupted()) return;
			handler.post(new UpdateRunnable(listener, uri, imageViewRef.get(), current, total));
		}

		private static class UpdateRunnable implements Runnable {

			private final ImageLoadingListener listener;
			private final String uri;
			private final ImageView view;
			private final int current, total;

			UpdateRunnable(final ImageLoadingListener listener, final String uri, final ImageView view,
					final int current, final int total) {
				this.listener = listener;
				this.uri = uri;
				this.view = view;
				this.current = current;
				this.total = total;
			}

			@Override
			public void run() {
				if (listener != null) {
					listener.onLoadingProgressChanged(uri, view, current, total);
				}
			}

		}

	}
}