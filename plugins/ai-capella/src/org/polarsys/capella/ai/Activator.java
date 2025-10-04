package org.polarsys.capella.ai;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;
import org.polarsys.capella.ai.session.SessionSafeguards;

public class Activator extends AbstractUIPlugin {
    public static final String PLUGIN_ID = "org.polarsys.capella.ai";
    private static Activator plugin;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        SessionSafeguards.cleanup();
        plugin = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return plugin;
    }
}
