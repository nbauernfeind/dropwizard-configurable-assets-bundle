package io.dropwizard.bundles.assets;

import com.google.common.base.CharMatcher;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheBuilderSpec;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.Weigher;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import io.dropwizard.servlets.assets.ResourceURL;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.NoSuchElementException;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Servlet responsible for serving assets to the caller.  This was originally based off of
 * {@link io.dropwizard.servlets.assets.AssetServlet}.
 *
 * @see io.dropwizard.servlets.assets.AssetServlet
 */
public class AssetServlet extends DefaultServlet {
  private static final long serialVersionUID = 6393345594784987908L;
  private static final CharMatcher SLASHES = CharMatcher.is('/');

  private final CacheBuilderSpec cacheSpec;
  private final LoadingCache<String, Resource> cache;

  private final AssetsConfiguration configuration;

  @Override
  public void init() throws UnavailableException {
    ServletContext servletContext = getServletContext();
    ContextHandler contextHandler = initContextHandler(servletContext);

    // TODO how do we validate this?
    // This servlet must be initialized during startup. Otherwise we cannot invoke setInitParameter
    // to configure the Default Servlet.
    if (!contextHandler.isStarting()) {
      super.init();
      return;
    }

    MimeTypes mimeTypes = contextHandler.getMimeTypes();
    for (Map.Entry<String, String> entry : configuration.getMimeTypes().entrySet()) {
      mimeTypes.addMimeMapping(entry.getKey(), entry.getValue());
    }
    contextHandler.setMimeTypes(mimeTypes);

    setInitParameter("cacheControl", configuration.getCacheControlHeader());
    setInitParameter("acceptRanges", configuration.serveAcceptRanges() ? "t" : "f");
    setInitParameter("etags", configuration.generateEtags() ? "t" : "f");
    setInitParameter("gzip", configuration.serveGzip() ? "t" : "f");

    boolean first = true;
    if (!configuration.getOtherGzipFileExtensions().isEmpty()) {
      StringBuilder strBuilder = new StringBuilder();
      for (String str : configuration.getOtherGzipFileExtensions()) {
        if (first) {
          first = false;
        } else {
          strBuilder.append(',');
        }
        strBuilder.append(str);
      }
      setInitParameter("otherGzipFileExtensions", strBuilder.toString());
    }

    super.init();
  }

  private void setInitParameter(String name, String value) {
    getServletContext().setInitParameter("org.eclipse.jetty.servlet.Default."+name, value);
  }

  /**
   * Creates a new {@code AssetServlet} that serves static assets loaded from {@code resourceURL}
   * (typically a file: or jar: URL). The assets are served at URIs rooted at {@code uriPath}. For
   * example, given a {@code resourceURL} of {@code "file:/data/assets"} and a {@code uriPath} of
   * {@code "/js"}, an {@code AssetServlet} would serve the contents of {@code
   * /data/assets/example.js} in response to a request for {@code /js/example.js}. If a directory
   * is requested and {@code indexFile} is defined, then {@code AssetServlet} will attempt to
   * serve a file with that name in that directory. If a directory is requested and {@code
   * indexFile} is null, it will serve a 404.
   *
   * @param configuration The configuration.
   */
  public AssetServlet(AssetsConfiguration configuration) {
    this.configuration = configuration;
    AssetLoader loader = new AssetLoader(configuration.getResourcePathToUriMappings().entrySet(),
            configuration.getIndexFile(), configuration.getOverrides().entrySet());

    CacheBuilder<Object, Object> cacheBuilder = CacheBuilder.from(configuration.getCacheSpec());
    // Don't add the weigher if we are using maximumSize instead of maximumWeight.
    if (configuration.getCacheSpec().contains("maximumWeight=")) {
      cacheBuilder.weigher(new AssetSizeWeigher());
    }

    // Let the cache spec from the configuration override the one specified in the code
    CacheBuilderSpec spec = CacheBuilderSpec.parse(configuration.getCacheSpec());
    this.cache = cacheBuilder.build(loader);
    this.cacheSpec = spec;
  }

  public CacheBuilderSpec getCacheSpec() {
    return cacheSpec;
  }

  @Override
  public Resource getResource(String pathInContext) {
    try {
      return cache.getUnchecked(pathInContext);
    } catch (Exception e) {
      return null;
    }
  }

  private static class AssetLoader extends CacheLoader<String, Resource> {
    private final String indexFilename;
    private final Map<String, String> resourcePathToUriMappings = Maps.newHashMap();
    private final Iterable<Map.Entry<String, String>> overrides;

    private AssetLoader(Iterable<Map.Entry<String, String>> resourcePathToUriMappings,
                        String indexFilename,
                        Iterable<Map.Entry<String, String>> overrides) {
      for (Map.Entry<String, String> mapping : resourcePathToUriMappings) {
        final String trimmedPath = SLASHES.trimFrom(mapping.getKey());
        String resourcePath = trimmedPath.isEmpty() ? trimmedPath : trimmedPath + '/';
        final String trimmedUri = SLASHES.trimTrailingFrom(mapping.getValue());
        String uriPath = trimmedUri.isEmpty() ? "/" : trimmedUri;

        if (this.resourcePathToUriMappings.containsKey(resourcePath)) {
          throw new IllegalArgumentException("ResourcePathToUriMappings contains multiple mappings "
                  + "for " + resourcePath);
        }
        this.resourcePathToUriMappings.put(resourcePath, uriPath);
      }

      this.indexFilename = indexFilename;
      this.overrides = overrides;
    }

    @Override
    public Resource load(String key) throws Exception {
      for (Map.Entry<String, String> mapping : resourcePathToUriMappings.entrySet()) {
        if (!key.startsWith(mapping.getValue())) {
          continue;
        }

        Resource asset = loadOverride(key);
        if (asset != null) {
          return asset;
        }

        final String requestedResourcePath =
                SLASHES.trimFrom(key.substring(mapping.getValue().length()));
        final String absolutePath = SLASHES.trimFrom(mapping.getKey() + requestedResourcePath);

        try {
          URL requestedResourceUrl =
              UrlUtil.switchFromZipToJarProtocolIfNeeded(Resources.getResource(absolutePath));
          if (ResourceURL.isDirectory(requestedResourceUrl)) {
            if (indexFilename != null) {
              requestedResourceUrl = Resources.getResource(absolutePath + '/' + indexFilename);
              requestedResourceUrl =
                  UrlUtil.switchFromZipToJarProtocolIfNeeded(requestedResourceUrl);
            } else {
              // resource mapped to directory but no index file defined
              continue;
            }
          }

          long lastModified = ResourceURL.getLastModified(requestedResourceUrl);
          if (lastModified < 1) {
            // Something went wrong trying to get the last modified time: just use the current time
            lastModified = System.currentTimeMillis();
          }

          // zero out the millis; the If-Modified-Since header will not have them
          lastModified = (lastModified / 1000) * 1000;
          return new StaticResource(requestedResourceUrl,
                  Resources.toByteArray(requestedResourceUrl), lastModified);
        } catch (IllegalArgumentException expected) {
          // Try another Mapping.
        }
      }

      throw new NoSuchElementException();
    }

    private Resource loadOverride(String key) throws Exception {
      // TODO: Support prefix matches only for directories
      for (Map.Entry<String, String> override : overrides) {
        File file = null;
        if (override.getKey().equals(key)) {
          // We have an exact match
          file = new File(override.getValue());
        } else if (key.startsWith(override.getKey())) {
          // This resource is in a mapped subdirectory
          file = new File(override.getValue(), key.substring(override.getKey().length()));
        }

        if (file == null || !file.exists()) {
          continue;
        }

        if (file.isDirectory()) {
          file = new File(file, indexFilename);
        }

        if (file.exists()) {
          return new FileSystemResource(file);
        }
      }

      return null;
    }
  }

  private interface AssetResource {
    byte[] getResource();
  }

  /**
   * Weigh an asset according to the number of bytes it contains.
   */
  private static final class AssetSizeWeigher implements Weigher<String, AssetResource> {
    @Override
    public int weigh(String key, AssetResource asset) {
      return asset.getResource().length;
    }
  }

  /**
   * An asset implementation backed by the file-system.  If the backing file changes on disk, then
   * this asset will automatically reload its contents from disk.
   */
  private static class FileSystemResource extends PathResource implements AssetResource {
    private final File file;
    private byte[] bytes;
    private long lastModifiedTime;

    private FileSystemResource(File file) {
      super(file);
      this.file = file;
      refresh();
    }

    @Override
    public byte[] getResource() {
      maybeRefresh();
      return bytes;
    }

    @Override
    public InputStream getInputStream() throws IOException {
      maybeRefresh();
      return new ByteArrayInputStream(getResource());
    }

    @Override
    public ReadableByteChannel getReadableByteChannel() throws IOException {
      return Channels.newChannel(getInputStream());
    }

    @Override
    public boolean exists() {
      return true;
    }

    private synchronized void maybeRefresh() {
      if (lastModifiedTime != file.lastModified()) {
        refresh();
      }
    }

    private synchronized void refresh() {
      try {
        bytes = Files.toByteArray(file);
        lastModifiedTime = file.lastModified();
      } catch (IOException e) {
        // Ignored, don't update anything
      }
    }
  }

  /**
   * A static asset implementation.  This implementation just encapsulates the raw bytes of an
   * asset (presumably loaded from the classpath) and will never change.
   */
  private static class StaticResource extends PathResource implements AssetResource {
    private final byte[] resource;
    private final long lastModifiedTime;

    private StaticResource(URL url, byte[] resource, long lastModifiedTime)
            throws IOException, URISyntaxException {
      super(url);
      this.resource = resource;
      this.lastModifiedTime = lastModifiedTime;
    }

    @Override
    public byte[] getResource() {
      return resource;
    }

    @Override
    public InputStream getInputStream() throws IOException {
      return new ByteArrayInputStream(getResource());
    }

    @Override
    public ReadableByteChannel getReadableByteChannel() throws IOException {
      return Channels.newChannel(getInputStream());
    }

    @Override
    public boolean exists() {
      return true;
    }

    @Override
    public long lastModified() {
      return lastModifiedTime;
    }
  }
}
