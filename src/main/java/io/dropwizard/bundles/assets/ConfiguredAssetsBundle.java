package io.dropwizard.bundles.assets;

import com.google.common.base.Charsets;
import com.google.common.cache.CacheBuilderSpec;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import io.dropwizard.ConfiguredBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * An assets bundle (like io.dropwizard.assets.AssetsBundle) that utilizes configuration to provide
 * the ability to override how assets are loaded and cached.  Specifying an override is useful
 * during the development phase to allow assets to be loaded directly out of source directories
 * instead of the classpath and to force them to not be cached by the browser or the server.  This
 * allows developers to edit an asset, save and then immediately refresh the web browser and see the
 * updated assets.  No compilation or copy steps are necessary.
 */
public class ConfiguredAssetsBundle implements ConfiguredBundle<AssetsBundleConfiguration> {
  private static final Logger LOGGER = LoggerFactory.getLogger(ConfiguredAssetsBundle.class);

  @Override
  public void initialize(Bootstrap<?> bootstrap) {
    // nothing to do
  }

  @Override
  public void run(AssetsBundleConfiguration bundleConfig, Environment env) throws Exception {
    AssetsConfiguration config = bundleConfig.getAssetsConfiguration();

    AssetServlet servlet = new AssetServlet(bundleConfig.getAssetsConfiguration());

    for (Map.Entry<String, String> mapping : config.getResourcePathToUriMappings().entrySet()) {
      String mappingPath = mapping.getValue();
      if (!mappingPath.endsWith("/")) {
        mappingPath += '/';
      }
      mappingPath += "*";
      LOGGER.info("Registering ConfiguredAssetBundle with name: {} for path {}",
          config.getServletName(), mappingPath);
      env.servlets().addServlet(config.getServletName(), servlet).addMapping(mappingPath);
    }
  }
}
