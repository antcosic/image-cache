package org.gbif.imgcache;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import javax.imageio.ImageIO;

import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closer;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ImageCacheService {

  private static final Logger LOG = LoggerFactory.getLogger(ImageCacheService.class);

  private final File repo;
  private static final String ENC = "UTF8";
  private static final String TARGET_FMT = "jpeg";
  private static final String MIME_TYPE = "image/" + TARGET_FMT;
  private static final String DFT_FILENAME = "image";
  private static final String HEAD_METHOD = "HEAD";
  private static final int TIMEOUT_MS = 2*60*1000;  // 2 minutes
  private static final int CONNECT_TIMEOUT_MS = 30*1000;  // 30 seconds

  @Inject
  public ImageCacheService(@Named("imgcache.repository") String repository) {
    repo = new File(repository);
    LOG.info("Use image repository {}", repo.getAbsolutePath());
    if (!repo.exists() && !repo.isDirectory() && !repo.canWrite()) {
      throw new IllegalStateException("imgcache.repository needs to be an existing, writable directory: "
        + repo.getAbsolutePath());
    }
  }

  public CachedImage get(URL url, ImageSize size) throws IOException {
    Preconditions.checkNotNull(url);

    File imgFile = location(url, size);
    if (!imgFile.exists()) {
      cacheImage(url);
    }
    // TODO: store mime type or deduce from image/file suffix
    return new CachedImage(url, size, MIME_TYPE, imgFile);
  }

  private static String buildFileName(URL url, ImageSize size) {
    // try to get some sensible filename - optional
    String fileName;
    try {
      fileName = new File(url.getPath()).getName();
    } catch (Exception e) {
      fileName = DFT_FILENAME;
    }

    if (size != ImageSize.ORIGINAL) {
      fileName += "-" + size.name().charAt(0) + "." + TARGET_FMT;
    }

    return fileName;
  }

  private void cacheImage(URL url) throws IOException {
    if (exists(url)) {
      // download original
      LOG.info("Caching image {}", url);
      copyOriginal(url);
      // now produce thumbnails from the original
      produceImage(url, ImageSize.THUMBNAIL, ImageSize.SMALL, ImageSize.MIDSIZE, ImageSize.LARGE);
    } else {
      throw new IOException(String.format("Requested file doesn't exist %s", url));
    }
  }

  /**
   * Creates a copy of the file with its original size.
   */
  private void copyOriginal(URL url) throws IOException {
    File origImg = location(url, ImageSize.ORIGINAL);

    OutputStream out = null;
    InputStream source = null;
    try (Closer closer = Closer.create()) {
      URLConnection con = url.openConnection();
      con.setConnectTimeout(CONNECT_TIMEOUT_MS);
      con.setReadTimeout(TIMEOUT_MS);
      source = closer.register(con.getInputStream());
      // create parent folder that is unique for the original image
      origImg.getParentFile().mkdir();
      out = closer.register(new FileOutputStream(origImg));
      ByteStreams.copy(source, out);
    }
  }

  /**
   * Checks if the remote URL exists.
   */
  private static boolean exists(URL url) {
    try {
      HttpURLConnection.setFollowRedirects(false);
      HttpURLConnection con = (HttpURLConnection) url.openConnection();
      con.setRequestMethod(HEAD_METHOD);
      return HttpURLConnection.HTTP_OK == con.getResponseCode();
    } catch (Exception e) {
      LOG.error(String.format("Error getting file %s", url), e);
      return false;
    }
  }

  /**
   * Creates a location for the image URL with a suffix for the specified size.
   */
  private File location(URL url, ImageSize size) throws IOException {
    File folder;
    try {
      folder = new File(repo, URLEncoder.encode(url.toString(), ENC));
    } catch (UnsupportedEncodingException e) {
      LOG.error("Error setting image location", e);
      throw new IOException("Encoding not supported", e);
    }
    return new File(folder, buildFileName(url, size));
  }

  /**
   * Produces an image for each size in the sizes parameter.
   */
  private void produceImage(URL url, ImageSize... sizes) throws IOException {
    for (ImageSize size : sizes) {
      createImage(url, size);
    }
  }

  /**
   * Produces a single image from the url with the specified size.
   */
  private void createImage(URL url, ImageSize size) throws IOException {
    File orig = location(url, ImageSize.ORIGINAL);
    File calc = location(url, size);
    BufferedImage bufferedImage = ImageIO.read(orig);
    Thumbnails.Builder<BufferedImage> thumb = Thumbnails.of(bufferedImage)
      .size(size.width, size.height)
      .outputFormat(TARGET_FMT)
      .outputQuality(0.85);

    // crop thumbnails to squares
    if (ImageSize.THUMBNAIL == size) {
      thumb.crop(Positions.CENTER);
    }

    // process
    thumb.toFile(calc);
    bufferedImage.flush();
  }
}
