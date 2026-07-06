package com.jarvis.integrations;

import com.jarvis.tools.Tool;
import java.util.List;

/**
 * Public contract for an integration plugin: self-describing metadata plus the tools it exposes to
 * agents. External capabilities extend the platform by implementing this interface — never by
 * modifying core modules.
 */
public interface Plugin {

    /** This plugin's identifying metadata; {@code descriptor().name()} is the registration key. */
    PluginDescriptor descriptor();

    /**
     * The tools this plugin contributes. Tool names must be unique within the plugin and must not
     * collide with tools already installed on the target registry.
     */
    List<Tool> tools();
}
