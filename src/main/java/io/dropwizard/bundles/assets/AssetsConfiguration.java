package io.dropwizard.bundles.assets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import javax.validation.constraints.NotNull;

@JsonDeserialize(builder = AssetsConfiguration.Builder.class)
public class AssetsConfiguration {
  public static final String SLASH = "/";

  public static final String DEFAULT_CACHE_SPEC = "maximumSize=100";

  @NotNull
  private final Map<String, String> mappings;

  @NotNull
  private final Map<String, String> overrides;

  @NotNull
  private final Map<String, String> mimeTypes;

  /**
   * Initialize cacheSpec to null so that whatever may be specified by code is able to be
   * by configuration. If null the default cache spec of "maximumSize=100" will be used.
   *
   * @see ConfiguredAssetsBundle#DEFAULT_CACHE_SPEC
   */

  private final String cacheSpec;

  // If set, all static content will have this value set as the cache-control header.
  private final String cacheControlHeader;

  // If true, range requests and responses are supported;
  private final boolean acceptRanges;

  // If true, weak etags will be generated and handled.
  private final boolean etags;

  // If true then static content will be served as gzip content encoded if a matching resource is
  // found ending with ".gz"
  private final boolean gzip;

  @NotNull
  private final Collection<String> otherGzipFileExtensions;

  private AssetsConfiguration(
          Map<String, String> mappings,
          Map<String, String> overrides,
          Map<String, String> mimeTypes,
          String cacheSpec,
          String cacheControlHeader,
          boolean acceptRanges,
          boolean etags,
          boolean gzip,
          Collection<String> otherGzipFileExtensions) {
    this.cacheSpec = cacheSpec;
    this.overrides = Collections.unmodifiableMap(overrides);
    this.mimeTypes = Collections.unmodifiableMap(mimeTypes);
    this.mappings = Collections.unmodifiableMap(mappings);
    this.acceptRanges = acceptRanges;
    this.gzip = gzip;
    this.etags = etags;
    this.cacheControlHeader = cacheControlHeader;
    this.otherGzipFileExtensions = Collections.unmodifiableCollection(otherGzipFileExtensions);
  }

  private String ensureEndsWithSlash(String value) {
    return value != null ? (value.endsWith(SLASH) ? value : value + SLASH) : SLASH;
  }

  protected Map<String, String> mappings() {
    return mappings;
  }

  public Map<String, String> getOverrides() {
    return Collections.unmodifiableMap(overrides);
  }

  public Map<String, String> getMimeTypes() {
    return Collections.unmodifiableMap(mimeTypes);
  }

  /**
   * The caching specification for how to memoize assets.
   *
   * @return The cacheSpec.
   */
  public String getCacheSpec() {
    return cacheSpec;
  }

  public String getCacheControlHeader() {
    return cacheControlHeader;
  }

  /**
   * A series of mappings from resource paths (in the classpath)
   * to the uri path that hosts the resource
   * @return The resourcePathToUriMappings.
   */
  public Map<String, String> getResourcePathToUriMappings() {
    ImmutableMap.Builder<String, String> mapBuilder = ImmutableMap.<String, String>builder();
    // Ensure that resourcePath and uri ends with a '/'
    for (Map.Entry<String, String> mapping : mappings().entrySet()) {
      mapBuilder
              .put(ensureEndsWithSlash(mapping.getKey()), ensureEndsWithSlash(mapping.getValue()));
    }
    return mapBuilder.build();
  }

  public boolean serveAcceptRanges() {
    return acceptRanges;
  }

  public boolean serveGzip() {
    return gzip;
  }

  public boolean generateEtags() {
    return etags;
  }

  public Collection<String> getOtherGzipFileExtensions() {
    return otherGzipFileExtensions;
  }

  public String getIndexFile() { return "index.html"; } // TODO

  public String getServletName() { return "assets"; } // TODO

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    @JsonProperty
    private Map<String, String> mappings = Maps.newHashMap();
    @JsonProperty
    private Map<String, String> overrides = Maps.newHashMap();
    @JsonProperty
    private Map<String, String> mimeTypes = Maps.newHashMap();
    @JsonProperty
    private String cacheSpec = "maximumSize=100";
    @JsonProperty
    private String cacheControlHeader = null;
    @JsonProperty
    private boolean acceptRanges = true;
    @JsonProperty
    private boolean etags = true;
    @JsonProperty
    private boolean gzip = false;
    @JsonProperty
    private Collection<String> otherGzipFileExtensions = Collections.emptyList();
    @JsonProperty
    private String indexFile = "index.htm";
    @JsonProperty
    private String servletName = null;

    private Builder() {}

    public Builder mappings(Map<String, String> mappings) {
      this.mappings = Preconditions.checkNotNull(mappings);
      return this;
    }

    public Builder mimeTypes(Map<String, String> mimeTypes) {
      this.mimeTypes = Preconditions.checkNotNull(mimeTypes);
      return this;
    }

    public Builder overrides(Map<String, String> overrides) {
      this.overrides = Preconditions.checkNotNull(overrides);
      return this;
    }

    public Builder cacheSpec(String cacheSpec) {
      this.cacheSpec = cacheSpec;
      return this;
    }

    public Builder cacheControlHeader(String cacheControlHeader) {
      this.cacheControlHeader = cacheControlHeader;
      return this;
    }

    public Builder acceptRanges(boolean acceptRanges) {
      this.acceptRanges = acceptRanges;
      return this;
    }

    public Builder etags(boolean etags) {
      this.etags = etags;
      return this;
    }

    public Builder gzip(boolean gzip) {
      this.gzip = gzip;
      return this;
    }

    public Builder otherGzipFileExtensions(Collection<String> otherGzipFileExtensions) {
      this.otherGzipFileExtensions = Preconditions.checkNotNull(otherGzipFileExtensions);
      return this;
    }

    public Builder indexFile(String indexFile) {
      this.indexFile = indexFile;
      return this;
    }

    public AssetsConfiguration build() {
      return new AssetsConfiguration(mappings, overrides, mimeTypes, cacheSpec, cacheControlHeader,
              acceptRanges, etags, gzip, otherGzipFileExtensions);
    }
  }

}
