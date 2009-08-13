/*
 * DPP - Serious Distributed Pair Programming
 * (c) Freie Universitaet Berlin - Fachbereich Mathematik und Informatik - 2006
 * (c) Riad Djemili - 2006
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 1, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package de.fu_berlin.inf.dpp;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.apache.log4j.helpers.LogLog;
import org.eclipse.core.net.proxy.IProxyData;
import org.eclipse.core.net.proxy.IProxyService;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.preferences.ConfigurationScope;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.jivesoftware.smack.AccountManager;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.ConnectionListener;
import org.jivesoftware.smack.Roster;
import org.jivesoftware.smack.RosterEntry;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.Roster.SubscriptionMode;
import org.jivesoftware.smack.packet.Registration;
import org.jivesoftware.smack.proxy.ProxyInfo;
import org.jivesoftware.smackx.ServiceDiscoveryManager;
import org.jivesoftware.smackx.packet.Jingle;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;
import org.picocontainer.Characteristics;
import org.picocontainer.MutablePicoContainer;
import org.picocontainer.PicoBuilder;
import org.picocontainer.PicoCompositionException;
import org.picocontainer.injectors.AnnotatedFieldInjection;
import org.picocontainer.injectors.CompositeInjection;
import org.picocontainer.injectors.ConstructorInjection;
import org.picocontainer.injectors.ProviderAdapter;
import org.picocontainer.injectors.Reinjector;

import de.fu_berlin.inf.dpp.annotations.Component;
import de.fu_berlin.inf.dpp.concurrent.undo.UndoManager;
import de.fu_berlin.inf.dpp.concurrent.watchdog.ConsistencyWatchdogClient;
import de.fu_berlin.inf.dpp.concurrent.watchdog.ConsistencyWatchdogServer;
import de.fu_berlin.inf.dpp.concurrent.watchdog.IsInconsistentObservable;
import de.fu_berlin.inf.dpp.concurrent.watchdog.SessionViewOpener;
import de.fu_berlin.inf.dpp.editor.EditorManager;
import de.fu_berlin.inf.dpp.editor.internal.EditorAPI;
import de.fu_berlin.inf.dpp.feedback.DataTransferCollector;
import de.fu_berlin.inf.dpp.feedback.FeedbackManager;
import de.fu_berlin.inf.dpp.feedback.ParticipantCollector;
import de.fu_berlin.inf.dpp.feedback.RoleChangeCollector;
import de.fu_berlin.inf.dpp.feedback.SessionDataCollector;
import de.fu_berlin.inf.dpp.feedback.StatisticManager;
import de.fu_berlin.inf.dpp.feedback.TextEditCollector;
import de.fu_berlin.inf.dpp.net.IConnectionListener;
import de.fu_berlin.inf.dpp.net.JID;
import de.fu_berlin.inf.dpp.net.RosterTracker;
import de.fu_berlin.inf.dpp.net.XMPPUtil;
import de.fu_berlin.inf.dpp.net.business.ActivitiesHandler;
import de.fu_berlin.inf.dpp.net.business.CancelInviteHandler;
import de.fu_berlin.inf.dpp.net.business.ChecksumHandler;
import de.fu_berlin.inf.dpp.net.business.ConsistencyWatchdogHandler;
import de.fu_berlin.inf.dpp.net.business.InvitationHandler;
import de.fu_berlin.inf.dpp.net.business.JoinHandler;
import de.fu_berlin.inf.dpp.net.business.LeaveHandler;
import de.fu_berlin.inf.dpp.net.business.RequestForActivityHandler;
import de.fu_berlin.inf.dpp.net.business.RequestForFileListHandler;
import de.fu_berlin.inf.dpp.net.business.UserListHandler;
import de.fu_berlin.inf.dpp.net.internal.DataTransferManager;
import de.fu_berlin.inf.dpp.net.internal.DiscoveryManager;
import de.fu_berlin.inf.dpp.net.internal.MultiUserChatManager;
import de.fu_berlin.inf.dpp.net.internal.SubscriptionListener;
import de.fu_berlin.inf.dpp.net.internal.XMPPChatReceiver;
import de.fu_berlin.inf.dpp.net.internal.XMPPChatTransmitter;
import de.fu_berlin.inf.dpp.net.internal.extensions.CancelInviteExtension;
import de.fu_berlin.inf.dpp.net.internal.extensions.ChecksumErrorExtension;
import de.fu_berlin.inf.dpp.net.internal.extensions.ChecksumExtension;
import de.fu_berlin.inf.dpp.net.internal.extensions.DataTransferExtension;
import de.fu_berlin.inf.dpp.net.internal.extensions.InviteExtension;
import de.fu_berlin.inf.dpp.net.internal.extensions.JoinExtension;
import de.fu_berlin.inf.dpp.net.internal.extensions.LeaveExtension;
import de.fu_berlin.inf.dpp.net.internal.extensions.PacketExtensionUtils;
import de.fu_berlin.inf.dpp.net.internal.extensions.RequestActivityExtension;
import de.fu_berlin.inf.dpp.net.internal.extensions.RequestForFileListExtension;
import de.fu_berlin.inf.dpp.net.internal.extensions.UserListExtension;
import de.fu_berlin.inf.dpp.observables.FileReplacementInProgressObservable;
import de.fu_berlin.inf.dpp.observables.InvitationProcessObservable;
import de.fu_berlin.inf.dpp.observables.JingleFileTransferManagerObservable;
import de.fu_berlin.inf.dpp.observables.SessionIDObservable;
import de.fu_berlin.inf.dpp.observables.SharedProjectObservable;
import de.fu_berlin.inf.dpp.optional.cdt.CDTFacade;
import de.fu_berlin.inf.dpp.optional.jdt.JDTFacade;
import de.fu_berlin.inf.dpp.preferences.PreferenceConstants;
import de.fu_berlin.inf.dpp.preferences.PreferenceManager;
import de.fu_berlin.inf.dpp.preferences.PreferenceUtils;
import de.fu_berlin.inf.dpp.project.ConnectionSessionManager;
import de.fu_berlin.inf.dpp.project.SarosRosterListener;
import de.fu_berlin.inf.dpp.project.SessionManager;
import de.fu_berlin.inf.dpp.project.SharedResourcesManager;
import de.fu_berlin.inf.dpp.project.internal.RoleManager;
import de.fu_berlin.inf.dpp.synchronize.StopManager;
import de.fu_berlin.inf.dpp.ui.SarosUI;
import de.fu_berlin.inf.dpp.util.StackTrace;
import de.fu_berlin.inf.dpp.util.Util;
import de.fu_berlin.inf.dpp.util.VersionManager;
import de.fu_berlin.inf.dpp.util.pico.ChildContainerProvider;
import de.fu_berlin.inf.dpp.util.pico.DotGraphMonitor;

/**
 * The main plug-in of Saros.
 * 
 * @author rdjemili
 * @author coezbek
 */
@Component(module = "core")
public class Saros extends AbstractUIPlugin {

    public static enum ConnectionState {

        /**
         * Saros not connected to a XMPP Server
         * 
         * Valid next states: CONNECTING (usually triggered by an user action to
         * connect)
         */
        NOT_CONNECTED {
            @Override
            public EnumSet<ConnectionState> getAllowedFollowState() {
                return EnumSet.of(ConnectionState.CONNECTING);
            }
        },

        /**
         * Saros is in the process of connecting
         * 
         * Valid next states:
         * 
         * - ERROR (if the attempt to connect failed)
         * 
         * - CONNECTED (if the attempt to connect was successful)
         */
        CONNECTING {
            @Override
            public EnumSet<ConnectionState> getAllowedFollowState() {
                return EnumSet.of(ConnectionState.CONNECTED,
                    ConnectionState.ERROR);
            }
        },

        /**
         * Saros is successfully connected to an XMPP server
         * 
         * Valid follow states:
         * 
         * - ERROR (if the connection broke)
         * 
         * - DISCONNECTING (if the user disconnected)
         */
        CONNECTED {
            @Override
            public EnumSet<ConnectionState> getAllowedFollowState() {
                return EnumSet.of(ConnectionState.DISCONNECTING,
                    ConnectionState.ERROR);
            }
        },

        /**
         * Saros is in the process of disconnecting
         * 
         * Valid follow states:
         * 
         * - NOT_CONNECTED
         */
        DISCONNECTING {
            @Override
            public EnumSet<ConnectionState> getAllowedFollowState() {
                return EnumSet.of(ConnectionState.NOT_CONNECTED);
            }
        },

        /**
         * There is an error in the XMPP connection.
         * 
         * Valid follow states:
         * 
         * - NOT_CONNECTED
         * 
         * - CONNECTING
         */
        ERROR() {
            @Override
            public EnumSet<ConnectionState> getAllowedFollowState() {
                return EnumSet.of(ConnectionState.NOT_CONNECTED,
                    ConnectionState.CONNECTING);
            }
        };

        public boolean isValidFollowState(ConnectionState newState) {
            return this.getAllowedFollowState().contains(newState);
        }

        public abstract EnumSet<ConnectionState> getAllowedFollowState();

    }

    /**
     * The single instance of the Saros plugin.
     */
    protected static Saros plugin;

    /**
     * True if the Saros instance has been initialized so that calling
     * reinject() will be well defined.
     */
    protected static boolean isInitialized;

    /**
     * This is the Bundle-SymbolicName
     */
    public static final String SAROS = "de.fu_berlin.inf.dpp"; //$NON-NLS-1$

    /**
     * The name of the XMPP namespace used by Saros. At the moment it is only
     * used to advertise the Saros feature in the Service Discovery.
     * 
     * TODO Add version information, so that only compatible versions of Saros
     * can use each other.
     */
    public final static String NAMESPACE = SAROS;

    /**
     * The name of the resource identifier used by Saros when connecting to the
     * XMPP Server (for instance when logging in as john@doe.com, Saros will
     * connect using john@doe.com/Saros)
     */
    public final static String RESOURCE = "Saros";

    public String sarosVersion;

    public String sarosFeatureID;

    protected SessionManager sessionManager;

    protected MutablePicoContainer container;

    /**
     * To print an architecture diagram at the end of the plug-in life-cycle
     * initialize the dotMonitor with a new instance:
     * 
     * <code>dotMonitor= new DotGraphMonitor();</code>
     */
    protected DotGraphMonitor dotMonitor;

    /**
     * The reinjector used to inject dependencies for those objects that are
     * created by Eclipse and not by our PicoContainer.
     */
    protected Reinjector reinjector;

    protected XMPPConnection connection;

    /**
     * The RQ-JID of the local user or null if the user is
     * {@link ConnectionState#NOT_CONNECTED}.
     */
    protected JID myJID;

    protected ConnectionState connectionState = ConnectionState.NOT_CONNECTED;

    protected Exception connectionError;

    protected final List<IConnectionListener> listeners = new CopyOnWriteArrayList<IConnectionListener>();

    // Smack (XMPP) connection listener
    protected ConnectionListener smackConnectionListener;

    /**
     * The global plug-in preferences, shared among all workspaces. Should only
     * be accessed over {@link #getConfigPrefs()} from outside this class.
     */
    protected Preferences configPrefs;

    protected Logger log;

    static {
        PacketExtensionUtils.hookExtensionProviders();
        Roster.setDefaultSubscriptionMode(SubscriptionMode.accept_all);
    }

    /**
     * Create the shared instance.
     */
    public Saros() {

        // Only start a DotGraphMonitor if asserts are enabled (aka debug mode)
        assert (dotMonitor = new DotGraphMonitor()) != null;

        isInitialized = false;
        setDefault(this);

        PicoBuilder picoBuilder = new PicoBuilder(new CompositeInjection(
            new ConstructorInjection(), new AnnotatedFieldInjection()))
            .withCaching().withLifecycle();

        /*
         * If given, the dotMonitor is used to capture an architecture diagram
         * of the application
         */
        if (dotMonitor != null) {
            picoBuilder = picoBuilder.withMonitor(dotMonitor);
        }

        // Initialize our dependency injection container
        this.container = picoBuilder.build();

        // Add Adapter which creates ChildContainers
        this.container.as(Characteristics.NO_CACHE).addAdapter(
            new ProviderAdapter(new ChildContainerProvider(this.container)));
        /*
         * All singletons which exist for the whole plug-in life-cycle are
         * managed by PicoContainer for us.
         * 
         * The addComponent() calls are sorted alphabetically according to the
         * first argument. This makes it easier to search for a class without
         * tool support.
         */
        this.container.addComponent(Saros.class, this);

        this.container.addComponent(CDTFacade.class);
        this.container.addComponent(ConnectionSessionManager.class);
        this.container.addComponent(ConsistencyWatchdogClient.class);
        this.container.addComponent(ConsistencyWatchdogServer.class);
        this.container.addComponent(SharedProjectObservable.class);
        this.container.addComponent(DataTransferManager.class);
        this.container.addComponent(DiscoveryManager.class);
        this.container.addComponent(EditorManager.class);
        this.container.addComponent(FeedbackManager.class);
        this.container.addComponent(IsInconsistentObservable.class);
        this.container.addComponent(JDTFacade.class);
        this.container.addComponent(JingleFileTransferManagerObservable.class);
        this.container.addComponent(MultiUserChatManager.class);
        this.container.addComponent(MessagingManager.class);
        this.container.addComponent(PreferenceManager.class);
        this.container.addComponent(PreferenceUtils.class);
        this.container.addComponent(RoleManager.class);
        this.container.addComponent(RosterTracker.class);
        this.container.addComponent(SarosRosterListener.class);
        this.container.addComponent(SarosUI.class);
        this.container.addComponent(SessionIDObservable.class);
        this.container.addComponent(SessionManager.class);
        this.container.addComponent(SessionViewOpener.class);
        this.container.addComponent(SharedResourcesManager.class);
        this.container.addComponent(SkypeManager.class);
        this.container.addComponent(StatisticManager.class);
        this.container.addComponent(StopManager.class);
        this.container.addComponent(SubscriptionListener.class);
        this.container.addComponent(UndoManager.class);
        this.container.addComponent(XMPPChatReceiver.class);
        this.container.addComponent(XMPPChatTransmitter.class);
        this.container.addComponent(VersionManager.class);

        // Observables
        this.container.addComponent(FileReplacementInProgressObservable.class);
        this.container.addComponent(InvitationProcessObservable.class);

        // Handlers
        this.container.addComponent(CancelInviteHandler.class);
        this.container.addComponent(UserListHandler.class);
        this.container.addComponent(InvitationHandler.class);
        this.container.addComponent(LeaveHandler.class);
        this.container.addComponent(RequestForActivityHandler.class);
        this.container.addComponent(ConsistencyWatchdogHandler.class);
        this.container.addComponent(ChecksumHandler.class);
        this.container.addComponent(JoinHandler.class);
        this.container.addComponent(RequestForFileListHandler.class);
        this.container.addComponent(ActivitiesHandler.class);

        // Extensions
        this.container.addComponent(ChecksumErrorExtension.class);
        this.container.addComponent(ChecksumExtension.class);
        this.container.addComponent(CancelInviteExtension.class);
        this.container.addComponent(UserListExtension.class);
        this.container.addComponent(RequestActivityExtension.class);
        this.container.addComponent(DataTransferExtension.class);
        this.container.addComponent(InviteExtension.class);
        this.container.addComponent(JoinExtension.class);
        this.container.addComponent(LeaveExtension.class);
        this.container.addComponent(RequestForFileListExtension.class);

        // Statistic collectors
        this.container.addComponent(DataTransferCollector.class);
        this.container.addComponent(RoleChangeCollector.class);
        this.container.addComponent(ParticipantCollector.class);
        this.container.addComponent(SessionDataCollector.class);
        this.container.addComponent(TextEditCollector.class);

        /*
         * The following classes are initialized by the re-injector because they
         * are created by Eclipse:
         * 
         * All User interface classes like all Views, but also
         * SharedDocumentProvider.
         * 
         * CAUTION: Classes from which duplicates can exists, should not be
         * managed by PicoContainer.
         */
        reinjector = new Reinjector(this.container);
    }

    /**
     * Injects dependencies into the annotated fields of the given object.
     */
    public static synchronized void reinject(Object toInjectInto) {

        if (plugin == null || !isInitialized()) {
            LogLog.error("Saros not initialized", new StackTrace());
            throw new IllegalStateException();
        }

        try {
            // Remove the component if an instance of it was already registered
            plugin.container.removeComponent(toInjectInto.getClass());

            // Add the given instance to the container
            plugin.container
                .addComponent(toInjectInto.getClass(), toInjectInto);

            /*
             * Ask PicoContainer to inject into the component via fields
             * annotated with @Inject
             */
            plugin.reinjector.reinject(toInjectInto.getClass(),
                new AnnotatedFieldInjection());
        } catch (PicoCompositionException e) {
            plugin.log.error("Internal error in reinjection:", e);
        }
    }

    /**
     * Returns true if the Saros instance has been initialized so that calling
     * {@link #reinject(Object)} will be well defined.
     */
    public static boolean isInitialized() {
        return isInitialized;
    }

    /**
     * This method is called upon plug-in activation
     */
    @Override
    public void start(BundleContext context) throws Exception {

        super.start(context);

        sarosVersion = Util.getBundleVersion(getBundle(), "Unknown Version");

        sarosFeatureID = SAROS + "_" + sarosVersion;

        XMPPConnection.DEBUG_ENABLED = getPreferenceStore().getBoolean(
            PreferenceConstants.DEBUG);

        setupLoggers();
        log.info("Starting Saros " + sarosVersion + " running:\n"
            + Util.getPlatformInfo());

        // Remove the Bundle if an instance of it was already registered
        container.removeComponent(Bundle.class);
        container.addComponent(Bundle.class, getBundle());

        // Make sure that all components in the container are
        // instantiated
        container.getComponents(Object.class);

        this.sessionManager = container.getComponent(SessionManager.class);

        isInitialized = true;

        // determine if auto-connect can and should be performed
        boolean autoConnect = getPreferenceStore().getBoolean(
            PreferenceConstants.AUTO_CONNECT);

        if (!autoConnect)
            return;

        // we need at least a user name, but also the agreement to the
        // statistic submission
        boolean hasUserName = this.container
            .getComponent(PreferenceUtils.class).hasUserName();
        boolean hasAgreement = this.container.getComponent(
            StatisticManager.class).hasStatisticAgreement();

        if (hasUserName && hasAgreement) {
            asyncConnect();
        }
    }

    /**
     * This method is called when the plug-in is stopped
     */
    @Override
    public void stop(BundleContext context) throws Exception {

        // TODO Devise a general way to stop and dispose our components
        saveConfigPrefs();

        if (dotMonitor != null) {
            File f = new File("Saros-" + sarosFeatureID + ".dot");
            log.info("Saving Saros architecture diagram dot file: "
                + f.getAbsolutePath());
            dotMonitor.save(f);
        }

        try {
            if (isConnected()) {
                /*
                 * Need to fork because disconnect should not be run in the SWT
                 * thread.
                 */

                /*
                 * FIXME Provide a unique thread context in which all
                 * connecting/disconnecting is done.
                 */
                Util.runSafeSyncFork(log, new Runnable() {
                    public void run() {
                        disconnect();
                    }
                });
            }

            /**
             * This will cause dispose() to be called on all components managed
             * by PicoContainer which implement {@link Disposable}.
             */
            container.dispose();
        } finally {
            super.stop(context);
        }

        isInitialized = false;
        setDefault(null);
    }

    public static void setDefault(Saros newPlugin) {
        Saros.plugin = newPlugin;

    }

    /**
     * The RQ-JID of the local user
     */
    public JID getMyJID() {
        return this.myJID;
    }

    public Roster getRoster() {
        if (!isConnected()) {
            return null;
        }

        return this.connection.getRoster();
    }

    /**
     * Returns the global {@link Preferences} with {@link ConfigurationScope}
     * for this plug-in or null if the node couldn't be determined. <br>
     * <br>
     * The returned Preferences can be accessed concurrently by multiple threads
     * of the same JVM without external synchronization. If they are used by
     * multiple JVMs no guarantees can be made concerning data consistency (see
     * {@link Preferences} for details).
     * 
     * @return the preferences node for this plug-in containing global
     *         preferences that are visible for all workspaces of this eclipse
     *         installation
     */
    public synchronized Preferences getConfigPrefs() {
        // TODO Singleton-Pattern code smell: ConfigPrefs should be a @component
        if (configPrefs == null) {
            configPrefs = new ConfigurationScope().getNode(SAROS);
        }
        return configPrefs;
    }

    /**
     * Saves the global preferences to disk. Should be called at least before
     * the bundle is stopped to prevent loss of data. Can be called whenever
     * found necessary.
     */
    public synchronized void saveConfigPrefs() {
        /*
         * Note: If multiple JVMs use the config preferences and the underlying
         * backing store, they might not always work with latest data, e.g. when
         * using multiple instances of the same eclipse installation.
         */
        if (configPrefs != null) {
            try {
                configPrefs.flush();
            } catch (BackingStoreException e) {
                log.error("Couldn't store global plug-in preferences", e);
            }
        }
    }

    /**
     * @nonBlocking
     */
    public void asyncConnect() {
        Util.runSafeAsync("Saros-AsyncConnect-", log, new Runnable() {
            public void run() {
                connect(false);
            }
        });
    }

    /**
     * Connects using the credentials from the preferences. It uses TLS if
     * possible.
     * 
     * If there is already a established connection when calling this method, it
     * disconnects before connecting (including state transitions!).
     * 
     * @blocking
     */
    public void connect(boolean failSilently) {

        IPreferenceStore prefStore = getPreferenceStore();

        final String server = prefStore.getString(PreferenceConstants.SERVER);
        final String username = prefStore
            .getString(PreferenceConstants.USERNAME);

        try {
            if (isConnected()) {
                disconnect();
            }

            this.connection = new XMPPConnection(getConnectionConfiguration());

            setConnectionState(ConnectionState.CONNECTING, null);

            this.connection.connect();

            // add connection listener so we get notified if it will be closed
            if (this.smackConnectionListener == null) {
                this.smackConnectionListener = new SafeConnectionListener(log,
                    new XMPPConnectionListener());
            }
            connection.addConnectionListener(this.smackConnectionListener);

            /* add Saros as XMPP feature */
            ServiceDiscoveryManager sdm = ServiceDiscoveryManager
                .getInstanceFor(connection);
            sdm.addFeature(Saros.NAMESPACE);

            // add Jingle feature to the supported extensions
            if (!prefStore
                .getBoolean(PreferenceConstants.FORCE_FILETRANSFER_BY_CHAT)) {
                // add Jingle Support for the current connection
                sdm.addFeature(Jingle.NAMESPACE);
            }

            Roster.setDefaultSubscriptionMode(Roster.SubscriptionMode.manual);

            String password = prefStore.getString(PreferenceConstants.PASSWORD);
            /*
             * TODO SS Possible race condition, as our packet listeners are
             * registered only after the login (in CONNECTED Connection State),
             * so we might for instance receive subscription requests even
             * though we do not have a packet listener running yet!
             */
            this.connection.login(username, password, Saros.RESOURCE);
            /* other people can now send invitations */

            this.myJID = new JID(this.connection.getUser());
            setConnectionState(ConnectionState.CONNECTED, null);

        } catch (final Exception e) {

            setConnectionState(ConnectionState.ERROR, e);

            if (!failSilently) {
                Util.runSafeSWTSync(log, new Runnable() {
                    public void run() {
                        MessageDialog.openError(EditorAPI.getShell(),
                            "Error Connecting", "Could not connect to server '"
                                + server + "' as user '" + username
                                + "'.\nErrorMessage was:\n" + e.getMessage());
                    }
                });
            }
        }
    }

    /**
     * Returns a @link{ConnectionConfiguration} representing the settings stored
     * in the Eclipse preferences.
     * 
     * This methods will fall back to use DNS SRV to get the XMPP port of the
     * server if the SERVER configured in the preferences does not have an
     * explicit port set.
     * 
     * Also this method configures the SecurityMode and the reconnection
     * attribute.
     * 
     * @throws URISyntaxException
     *             If the server string in the preferences cannot be transformed
     *             into an URI
     */
    protected ConnectionConfiguration getConnectionConfiguration()
        throws URISyntaxException {

        IPreferenceStore prefStore = getPreferenceStore();

        String serverString = prefStore.getString(PreferenceConstants.SERVER);

        URI uri = new URI("jabber://" + serverString);

        String server = uri.getHost();
        if (server == null) {
            throw new URISyntaxException(prefStore
                .getString(PreferenceConstants.SERVER),
                "The XMPP server address is invalid: " + server);
        }

        ProxyInfo proxyInfo = getProxyInfo(uri.getHost());
        ConnectionConfiguration conConfig = null;

        if (uri.getPort() < 0) {
            conConfig = proxyInfo == null ? new ConnectionConfiguration(uri
                .getHost()) : new ConnectionConfiguration(uri.getHost(),
                proxyInfo);
        } else {
            conConfig = proxyInfo == null ? new ConnectionConfiguration(uri
                .getHost(), uri.getPort()) : new ConnectionConfiguration(uri
                .getHost(), uri.getPort(), proxyInfo);
        }

        /*
         * TODO It has to ask the user, if s/he wants to use non-TLS connections
         * with PLAIN SASL if TLS is not supported by the server.
         * 
         * TODO use MessageDialog and Util.runSWTSync() to provide a password
         * call-back if the user has no password set in the preferences.
         */
        conConfig.setSecurityMode(ConnectionConfiguration.SecurityMode.enabled);
        /*
         * We handle reconnecting ourselves
         */
        conConfig.setReconnectionAllowed(false);

        return conConfig;
    }

    /**
     * Returns @link{IProxyService} if there is a registered service otherwise
     * null.
     */
    protected IProxyService getProxyService() {
        BundleContext bundleContext = getBundle().getBundleContext();
        ServiceReference serviceReference = bundleContext
            .getServiceReference(IProxyService.class.getName());
        return (IProxyService) bundleContext.getService(serviceReference);
    }

    /**
     * Returns a @link{ProxyInfo}, if a configuration of a proxy for the given
     * host is available. If @link{IProxyData} is of type
     * 
     * @link{IProxyData.HTTP_PROXY_TYPE it tries to use Smacks
     * @link{ProxyInfo.forHttpProxy and if it is of type
     * @link{IProxyData.SOCKS_PROXY_TYPE then it tries to use Smacks
     * @link{ProxyInfo.forSocks5Proxy otherwise it returns null.
     * 
     * @param host
     *            The host to which you want to connect to.
     * 
     * @return Returns a @link{ProxyInfo} if available otherwise null.
     */
    protected ProxyInfo getProxyInfo(String host) {
        IProxyService ips = getProxyService();
        if (ips == null)
            return null;
        for (IProxyData pd : ips.getProxyDataForHost(host)) {
            if (pd.getType() == IProxyData.HTTP_PROXY_TYPE) {
                return ProxyInfo.forHttpProxy(pd.getHost(), pd.getPort(), pd
                    .getUserId(), pd.getPassword());
            } else if (pd.getType() == IProxyData.SOCKS_PROXY_TYPE) {
                return ProxyInfo.forSocks5Proxy(pd.getHost(), pd.getPort(), pd
                    .getUserId(), pd.getPassword());

            }
        }

        return null;
    }

    /**
     * Disconnects (if currently connected)
     * 
     * @blocking
     * 
     * @post this.myjid == null && this.connection == null &&
     *       this.connectionState == ConnectionState.NOT_CONNECTED
     */
    public void disconnect() {
        if (isConnected()) {
            setConnectionState(ConnectionState.DISCONNECTING, null);

            disconnectInternal();

            setConnectionState(ConnectionState.NOT_CONNECTED, null);
        }
        this.myJID = null;

        // Make a sanity check on the connection and connection state
        if (this.connectionState != ConnectionState.NOT_CONNECTED) {
            log.warn("Connection state is out of sync");
            this.connectionState = ConnectionState.NOT_CONNECTED;
        }
        if (this.connection != null) {
            log.warn("Connection has not been closed");
            this.connection = null;
        }
    }

    protected void disconnectInternal() {
        if (connection != null) {
            try {
                connection.removeConnectionListener(smackConnectionListener);
                connection.disconnect();
            } catch (RuntimeException e) {
                log.warn("Could not disconnect old XMPPConnection: ", e);
            } finally {
                connection = null;
            }
        }
    }

    /**
     * Creates the given account on the given Jabber server.
     * 
     * @blocking
     * 
     * @param server
     *            the server on which to create the account.
     * @param username
     *            the username for the new account.
     * @param password
     *            the password for the new account.
     * @param monitor
     *            the progressmonitor for the operation.
     * @throws XMPPException
     *             exception that occurs while registering.
     */
    public void createAccount(String server, String username, String password,
        IProgressMonitor monitor) throws XMPPException {

        monitor.beginTask("Registering account", 3);

        try {
            XMPPConnection connection = new XMPPConnection(server);
            monitor.worked(1);

            connection.connect();
            monitor.worked(1);

            Registration registration = null;
            try {
                registration = XMPPUtil.getRegistrationInfo(username,
                    connection);
            } catch (XMPPException e) {
                log.error("Server " + server + " does not support XEP-0077"
                    + " (In-Band Registration) properly:", e);
            }
            if (registration != null) {
                if (registration.getAttributes().containsKey("registered")) {
                    throw new XMPPException("Account " + username
                        + " already exists on server");
                }
                if (!registration.getAttributes().containsKey("username")) {
                    String instructions = registration.getInstructions();
                    if (instructions != null) {
                        throw new XMPPException(
                            "Registration via Saros not possible. Please follow these instructions:\n"
                                + instructions);
                    } else {
                        throw new XMPPException(
                            "Registration via Saros not supported by Server. Please see the server web site for informations for how to create an account");
                    }
                }
            }
            monitor.worked(1);

            AccountManager manager = connection.getAccountManager();
            manager.createAccount(username, password);
            monitor.worked(1);

            connection.disconnect();
        } finally {
            monitor.done();
        }
    }

    /**
     * Adds given contact to the roster.
     * 
     * @blocking
     * 
     * @param jid
     *            the Jabber ID of the contact.
     * @param nickname
     *            the nickname under which the new contact should appear in the
     *            roster.
     * @param groups
     *            the groups to which the new contact should belong to. This
     *            information will be saved on the server.
     * @param monitor
     *            a SubMonitor to report progress to
     * @throws XMPPException
     *             is thrown if no connection is established or an error
     *             occurred when adding the user to the roster (which does not
     *             mean that the user really exists on the server)
     */
    public void addContact(JID jid, String nickname, String[] groups,
        SubMonitor monitor) throws XMPPException {

        monitor.beginTask("Adding contact " + jid + " to Roster..", 2);

        try {
            assertConnection();

            monitor.worked(1);

            // if roster already contains user with this jid, throw an exception
            if (connection.getRoster().contains(jid.toString())) {
                monitor.worked(1);

                throw new XMPPException("RosterEntry for user " + jid
                    + " already exists");
            }
            monitor.worked(1);

            connection.getRoster()
                .createEntry(jid.toString(), nickname, groups);
        } finally {
            monitor.done();
        }
    }

    /**
     * Given an XMPP Exception this method will return whether the exception
     * thrown by isJIDonServer indicates that the server does not support
     * ServiceDisco.<br>
     * <br>
     * In other words: If isJIDonServer throws an Exception and this method
     * returns true on the exception, then we should call addContact anyway.
     * 
     * @return true, if the exception occurred because the server does not
     *         support ServiceDiscovery
     */
    public static boolean isDiscoFailedException(XMPPException e) {

        /* feature-not-implemented */
        if (e.getMessage().contains("501"))
            return true;

        /* service-unavailable */
        if (e.getMessage().contains("503"))
            return true;

        return false;
    }

    /**
     * Returns whether the given JID can be found on the server.
     * 
     * @blocking
     * @cancelable
     * 
     * @param monitor
     *            a SubMonitor to report progress to
     * @throws XMPPException
     *             if the service disco failed. Use isDiscoFailedException to
     *             figure out, whether this might mean that the server does not
     *             support disco at all.
     */
    public boolean isJIDonServer(JID jid, SubMonitor monitor)
        throws XMPPException {
        monitor.beginTask("", 2);

        ServiceDiscoveryManager sdm = new ServiceDiscoveryManager(connection);
        monitor.worked(1);

        if (monitor.isCanceled())
            throw new CancellationException();

        try {
            boolean discovered = sdm.discoverInfo(jid.toString())
                .getIdentities().hasNext();
            /*
             * discovery does not change any state, if the user wanted to cancel
             * it, we can do that even after the execution finished
             */
            if (monitor.isCanceled())
                throw new CancellationException();
            return discovered;
        } finally {
            monitor.done();
        }
    }

    /**
     * Removes given contact from the roster.
     * 
     * @blocking
     * 
     * @param rosterEntry
     *            the contact that is to be removed
     * @throws XMPPException
     *             is thrown if no connection is established.
     */
    public void removeContact(RosterEntry rosterEntry) throws XMPPException {
        assertConnection();
        this.connection.getRoster().removeEntry(rosterEntry);
    }

    public boolean isConnected() {
        return this.connectionState == ConnectionState.CONNECTED;
    }

    /**
     * @return the current state of the connection.
     */
    public ConnectionState getConnectionState() {
        return this.connectionState;
    }

    /**
     * @return an error string that contains the error message for the current
     *         connection error if the state is {@link ConnectionState#ERROR} or
     *         <code>null</code> if there is another state set.
     */
    public Exception getConnectionError() {
        return this.connectionError;
    }

    /**
     * @return the currently established connection or <code>null</code> if
     *         there is none.
     */
    public XMPPConnection getConnection() {
        return this.connection;
    }

    public void addListener(IConnectionListener listener) {
        if (!this.listeners.contains(listener)) {
            this.listeners.add(listener);
        }
    }

    public void removeListener(IConnectionListener listener) {
        this.listeners.remove(listener);
    }

    protected void assertConnection() throws XMPPException {
        if (!isConnected()) {
            throw new XMPPException("No connection");
        }
    }

    /**
     * Sets a new connection state and notifies all connection listeners.
     */
    protected void setConnectionState(ConnectionState state, Exception error) {

        this.connectionState = state;
        this.connectionError = error;

        // Prefix the name of the user for which the state changed
        String prefix = "";
        if (connection != null) {
            String user = connection.getUser();
            if (user != null)
                prefix = Util.prefix(new JID(user));
        }

        if (error == null) {
            log.debug(prefix + "New Connection State == " + state);
        } else {
            log.error(prefix + "New Connection State == " + state, error);
        }

        for (IConnectionListener listener : this.listeners) {
            try {
                listener.connectionStateChanged(this.connection, state);
            } catch (RuntimeException e) {
                log.error("Internal error in setConnectionState:", e);
            }
        }
    }

    protected void setupLoggers() {
        try {
            log = Logger.getLogger("de.fu_berlin.inf.dpp");
            PropertyConfigurator.configureAndWatch("log4j.properties",
                60 * 1000);
        } catch (SecurityException e) {
            System.err.println("Could not start logging:");
            e.printStackTrace();
        }
    }

    /**
     * Log a message to the Eclipse ErrorLog. This method should be used to log
     * all errors that occur in the plugin that cannot be corrected by the user
     * and seem to be errors within the plug-in or the used libraries.
     * 
     * @param message
     *            A meaningful description of during which operation the error
     *            occurred
     * @param e
     *            The exception associated with the error (may be null)
     */
    public static void log(String message, Exception e) {
        plugin.getLog().log(
            new Status(IStatus.ERROR, Saros.SAROS, IStatus.ERROR, message, e));
    }

    protected class XMPPConnectionListener implements ConnectionListener {

        public void connectionClosed() {
            // self inflicted, controlled disconnect
            setConnectionState(ConnectionState.NOT_CONNECTED, null);
        }

        public void connectionClosedOnError(Exception e) {

            log.error("XMPP Connection Error: " + e.toString(), e);

            if (e.toString().equals("stream:error (conflict)")) {

                disconnect();

                Util.runSafeSWTSync(log, new Runnable() {
                    public void run() {
                        MessageDialog
                            .openError(
                                EditorAPI.getShell(),
                                "Connection error",
                                "You have been disconnected from Jabber, because of a resource conflict.\n"
                                    + "This indicates that you might have logged on again using the same Jabber account"
                                    + " and XMPP resource, for instance using Saros or an other instant messaging client.");
                    }
                });
                return;
            }

            // Only try to reconnect if we did achieve a connection...
            if (getConnectionState() != ConnectionState.CONNECTED)
                return;

            setConnectionState(ConnectionState.ERROR, e);

            disconnectInternal();

            Util.runSafeAsync(log, new Runnable() {
                public void run() {

                    Map<JID, Integer> expectedSequenceNumbers = Collections
                        .emptyMap();
                    if (sessionManager.getSharedProject() != null) {
                        expectedSequenceNumbers = sessionManager
                            .getSharedProject().getSequencer()
                            .getExpectedSequenceNumbers();
                    }

                    // HACK Improve this hack to stop an infinite reconnect
                    int i = 0;
                    final int CONNECTION_RETRIES = 15;

                    while (!isConnected() && i++ < CONNECTION_RETRIES) {

                        log.info("Reconnecting...");

                        try {
                            connect(true);
                            if (!isConnected())
                                Thread.sleep(5000);

                        } catch (InterruptedException e) {
                            log.error("Code not designed to be interruptable",
                                e);
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }

                    if (isConnected()) {
                        sessionManager.onReconnect(expectedSequenceNumbers);
                        log.debug("XMPP reconnected");
                    }
                }
            });
        }

        public void reconnectingIn(int seconds) {
            // TODO maybe using Smack reconnect is better
            assert false : "Reconnecting is disabled";
            // setConnectionState(ConnectionState.CONNECTING, null);
        }

        public void reconnectionFailed(Exception e) {
            // TODO maybe using Smack reconnect is better
            assert false : "Reconnecting is disabled";
            // setConnectionState(ConnectionState.ERROR, e.getMessage());
        }

        public void reconnectionSuccessful() {
            // TODO maybe using Smack reconnect is better
            assert false : "Reconnecting is disabled";
            // setConnectionState(ConnectionState.CONNECTED, null);
        }
    }

    /**
     * Returns a string representing the Saros Version number for instance
     * "9.5.7.r1266"
     * 
     * This method only returns a valid version string after the plugin has been
     * started.
     * 
     * This is equivalent to the bundle version.
     */
    public String getVersion() {
        return sarosVersion;
    }

    public static boolean getFileTransferModeViaChat() {
        return plugin.getPreferenceStore().getBoolean(
            PreferenceConstants.FORCE_FILETRANSFER_BY_CHAT);
    }
}
