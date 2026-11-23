/**
 * API for replacing the server's built-in storage.
 * <p>
 * There are four things that can be stored by a plugin:
 * <ul>
 *     <li><b>Worlds created by plugins</b>, with
 *     {@link org.bukkit.WorldCreator#storage(sh.btx.archipelago.storage.WorldStorage)}. Any plugin can do this.</li>
 *     <li><b>Default worlds</b> (the overworld, nether, end and dimensions from datapacks), with a
 *     {@link sh.btx.archipelago.storage.WorldStorageProvider}.</li>
 *     <li><b>Player data, statistics and advancements</b>, with a {@link sh.btx.archipelago.storage.PlayerStorage}.</li>
 *     <li><b>Maps</b>, with a {@link sh.btx.archipelago.storage.MapStorage}.</li>
 * </ul>
 * The last three are read before plugins are loaded, so they are registered from a bootstrapper, which needs a
 * {@code paper-plugin.yml}:
 * <pre>{@code
 * public final class MyBootstrap implements PluginBootstrap {
 *     @Override
 *     public void bootstrap(BootstrapContext context) {
 *         context.getLifecycleManager().registerEventHandler(LifecycleEvents.STORAGE, event -> {
 *             event.registrar().worlds(key -> new MyWorldStorage(key));
 *             event.registrar().players(new MyPlayerStorage());
 *             event.registrar().maps(new MyMapStorage());
 *         });
 *     }
 * }
 * }</pre>
 * <p>
 * Data that belongs to the server as a whole stays in the level folder: {@code level.dat}, the scoreboard, command
 * storage, saved structures and {@code session.lock}. So do maps when no {@link sh.btx.archipelago.storage.MapStorage}
 * is registered.
 *
 * <h2>Rules for every implementation</h2>
 * <ul>
 *     <li>{@code null} means that there is no data. Throw if data could not be read: returning {@code null} for data
 *     that exists makes the server replace it with new data.</li>
 *     <li>Never wait for the main thread and never use the Bukkit scheduler. The server blocks the main thread while
 *     it waits for storage, and plugins are disabled before the last save of a shutdown.</li>
 *     <li>Keep what the storage needs (connections, executors) until it is closed. That is after
 *     {@link org.bukkit.plugin.java.JavaPlugin#onDisable()} when the server stops. The plugin's classes can still
 *     be loaded until then.</li>
 *     <li>Use timeouts. The server waits for as long as a storage takes.</li>
 * </ul>
 */
@NullMarked
package sh.btx.archipelago.storage;

import org.jspecify.annotations.NullMarked;
