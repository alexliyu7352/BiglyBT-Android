/*
 * Copyright (c) Azureus Software, Inc, All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package com.biglybt.android.client.session;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import com.biglybt.android.client.*;
import com.biglybt.android.client.rpc.*;
import com.biglybt.android.util.BiglyCoreUtils;
import com.biglybt.android.util.MapUtils;
import com.biglybt.android.util.NetworkState;
import com.biglybt.util.Thunk;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentActivity;
import android.support.v7.app.AlertDialog;
import android.text.Spanned;
import android.util.Log;

import jcifs.netbios.NbtAddress;

import net.i2p.android.ui.I2PAndroidHelper;

/**
 * Access to all the information for a session, such as:<P>
 * - RemoteProfile<BR>
 * - SessionSettings<BR>
 * - RPC<BR>
 * - torrents<BR>
 */
public class Session
	implements SessionSettingsReceivedListener, NetworkState.NetworkStateListener
{
	private static final String TAG = "Session";

	public interface RpcExecuter
	{
		void executeRpc(TransmissionRPC rpc);
	}

	private static final String[] FILE_FIELDS_LOCALHOST = new String[] {
		TransmissionVars.FIELD_FILES_NAME,
		TransmissionVars.FIELD_FILES_LENGTH,
		TransmissionVars.FIELD_FILESTATS_BYTES_COMPLETED,
		TransmissionVars.FIELD_FILESTATS_PRIORITY,
		TransmissionVars.FIELD_FILESTATS_WANTED,
		TransmissionVars.FIELD_FILES_FULL_PATH,
	};

	private class HandlerRunnable
		implements Runnable
	{
		public void run() {
			handler = null;

			if (destroyed) {
				if (AndroidUtils.DEBUG) {
					logd("Handler ignored: destroyed");
				}
				return;
			}

			long interval = remoteProfile.calcUpdateInterval();
			if (interval <= 0) {
				if (AndroidUtils.DEBUG) {
					logd("Handler ignored: update interval " + interval);
				}
				cancelRefreshHandler();
				return;
			}

			if (isActivityVisible()) {
				if (AndroidUtils.DEBUG_ANNOY) {
					logd("Fire Handler");
				}
				triggerRefresh(true);

				for (RefreshTriggerListener l : refreshTriggerListeners) {
					l.triggerRefresh();
				}
			}
		}
	}

	private final String[] FILE_FIELDS_REMOTE = new String[] {
		TransmissionVars.FIELD_FILES_NAME,
		TransmissionVars.FIELD_FILES_LENGTH,
		TransmissionVars.FIELD_FILESTATS_BYTES_COMPLETED,
		TransmissionVars.FIELD_FILESTATS_PRIORITY,
		TransmissionVars.FIELD_FILESTATS_WANTED,
	};

	private static final String[] SESSION_STATS_FIELDS = {
		TransmissionVars.TR_SESSION_STATS_DOWNLOAD_SPEED,
		TransmissionVars.TR_SESSION_STATS_UPLOAD_SPEED
	};

	private SessionSettings sessionSettings;

	private boolean activityVisible;

	@Thunk
	TransmissionRPC transmissionRPC;

	@Thunk
	final RemoteProfile remoteProfile;

	@Thunk
	final Object mLock = new Object();

	private final List<SessionSettingsChangedListener> sessionSettingsChangedListeners = new CopyOnWriteArrayList<>();

	@Thunk
	final List<RefreshTriggerListener> refreshTriggerListeners = new CopyOnWriteArrayList<>();

	private final List<SessionListener> availabilityListeners = new CopyOnWriteArrayList<>();

	@Thunk
	Handler handler;

	private boolean readyForUI = false;

	private Map<?, ?> mapSessionStats;

	private String rpcRoot;

	private final List<RpcExecuter> rpcExecuteList = new ArrayList<>();

	private String baseURL;

	@Thunk
	FragmentActivity currentActivity;

	@Thunk
	boolean destroyed = false;

	@Thunk
	Map mapSupports = new HashMap<>();

	/**
	 * Access to Subscription methods
	 */
	public final Session_Subscription subscription = new Session_Subscription(
			this);

	/**
	 * Access to RCM (Swarm Discoveries) methods
	 */
	public final Session_RCM rcm = new Session_RCM(this);

	/**
	 * Access to Tag methods
	 */
	public final Session_Tag tag = new Session_Tag(this);

	/**
	 * Access to Torrent methods
	 */
	public final Session_Torrent torrent = new Session_Torrent(this);

	private long contentPort;

	private final Runnable handlerRunnable;

	private long lastRefreshInterval = -1;

	public Session(final @NonNull RemoteProfile _remoteProfile,
			FragmentActivity currentActivity) {
		this.remoteProfile = _remoteProfile;
		setCurrentActivity(currentActivity);

		handlerRunnable = new HandlerRunnable();

		if (AndroidUtils.DEBUG) {
			Log.d(TAG,
					"Session: init from " + AndroidUtils.getCompressedStackTrace());
		}

		Object lastSessionProperties = remoteProfile.get("lastSessionProperties",
				null);
		if (lastSessionProperties instanceof Map) {
			Object supports = ((Map) lastSessionProperties).get("supports");
			if (supports instanceof Map) {
				mapSupports = (Map) supports;
			}
		}

		BiglyBTApp.getNetworkState().addListener(this);

		// Bind and Open take a while, do it on the non-UI thread
		Thread thread = new Thread("bindAndOpen") {
			public void run() {
				String host = remoteProfile.getHost();
				if (host != null && host.endsWith(".i2p")) {
					bindToI2P(host, remoteProfile.getPort(), null, null, true);
					return;
				}
				if (host != null && host.length() > 0
						&& remoteProfile.getRemoteType() != RemoteProfile.TYPE_LOOKUP) {
					open(remoteProfile.getProtocol(), host, remoteProfile.getPort());
				} else {
					bindAndOpen(remoteProfile.getI2POnly());
				}
			}
		};
		thread.setDaemon(true);
		thread.start();

	}

	@Thunk
	void bindAndOpen(final boolean requireI2P) {

		try {
			Map<?, ?> bindingInfo = RPC.getBindingInfo(remoteProfile);

			Map<?, ?> error = MapUtils.getMapMap(bindingInfo, "error", null);
			if (error != null) {
				String errMsg = MapUtils.getMapString(error, "msg", "Unknown Error");
				if (AndroidUtils.DEBUG) {
					logd("Error from getBindingInfo " + errMsg);
				}

				AndroidUtilsUI.showConnectionError(currentActivity, errMsg, false);
				return;
			}

			final String host = MapUtils.getMapString(bindingInfo, "ip", null);
			final String protocol = MapUtils.getMapString(bindingInfo, "protocol",
					null);
			final String i2p = MapUtils.getMapString(bindingInfo, "i2p", null);
			final int port = (int) MapUtils.parseMapLong(bindingInfo, "port", 0);

			if (port != 0) {
				if (i2p != null) {
					if (bindToI2P(i2p, port, host, protocol, requireI2P)) {
						return;
					}
					if (requireI2P) {
						// User would have got a fail message from bindToI2P
						return;
					}
				} else if (requireI2P) {
					AndroidUtilsUI.showConnectionError(currentActivity,
							currentActivity.getString(R.string.i2p_remote_client_needs_i2p),
							false);
					return;
				}
				if (open(protocol, host, port)) {
					Map<String, Object> lastBindingInfo = new HashMap<>();
					lastBindingInfo.put("ip", host);
					lastBindingInfo.put("i2p", i2p);
					lastBindingInfo.put("port", port);
					lastBindingInfo.put("protocol",
							protocol == null || protocol.length() == 0 ? "http" : protocol);
					remoteProfile.setLastBindingInfo(lastBindingInfo);
					saveProfile();
				}
			}
		} catch (final RPCException e) {
			AnalyticsTracker.getInstance(currentActivity).logErrorNoLines(e);

			AndroidUtilsUI.showConnectionError(currentActivity, remoteProfile.getID(),
					e, false);
		}
	}

	@Thunk
	boolean bindToI2P(final String hostI2P, final int port,
			@Nullable final String hostFallBack,
			@Nullable final String protocolFallBack, final boolean requireI2P) {
		{
			if (currentActivity == null) {
				Log.e(TAG, "bindToI2P: currentActivity null");
				return false;
			}
			final I2PAndroidHelper i2pHelper = new I2PAndroidHelper(currentActivity);
			if (i2pHelper.isI2PAndroidInstalled()) {
				i2pHelper.bind(new I2PAndroidHelper.Callback() {
					@Override
					public void onI2PAndroidBound() {
						// We are now on the UI Thread :(
						new Thread(new Runnable() {
							@Override
							public void run() {
								Session.this.onI2PAndroidBound(i2pHelper, hostI2P, port,
										hostFallBack, protocolFallBack, requireI2P);
							}
						}).start();
					}
				});
				return true;
			} else if (requireI2P) {
				AndroidUtilsUI.showConnectionError(currentActivity,
						currentActivity.getString(R.string.i2p_not_installed), false);
				i2pHelper.unbind();
			} else if (AndroidUtils.DEBUG) {
				i2pHelper.unbind();
				logd("onI2PAndroidBound: I2P not installed");
			}
		}
		return false;
	}

	@Thunk
	void onI2PAndroidBound(final I2PAndroidHelper i2pHelper, String hostI2P,
			int port, String hostFallBack, String protocolFallback,
			boolean requireI2P) {
		boolean isI2PRunning = i2pHelper.isI2PAndroidRunning();

		if (AndroidUtils.DEBUG) {
			logd("onI2PAndroidBound: I2P running? " + isI2PRunning);
		}

		if (!isI2PRunning) {
			if (requireI2P) {
//				activity.runOnUiThread(new Runnable() {
//					@Override
//					public void run() {
//						i2pHelper.requestI2PAndroidStart(activity);
//					}
//				});
				AndroidUtilsUI.showConnectionError(currentActivity,
						currentActivity.getString(R.string.i2p_not_running), false);
				i2pHelper.unbind();
				return;
			}
		}
		if (!i2pHelper.areTunnelsActive() && requireI2P) {
			AndroidUtilsUI.showConnectionError(currentActivity,
					currentActivity.getString(R.string.i2p_no_tunnels), false);
			i2pHelper.unbind();
			return;
		}
		i2pHelper.unbind();

		boolean opened = false;
		if (isI2PRunning) {
			opened = open("http", hostI2P, port);
		}
		if (!opened && hostFallBack != null) {
			opened = open(protocolFallback, hostFallBack, port);
		}

		if (opened && hostFallBack != null) {
			Map<String, Object> lastBindingInfo = new HashMap<>();
			lastBindingInfo.put("ip", hostFallBack);
			lastBindingInfo.put("i2p", hostI2P);
			lastBindingInfo.put("port", port);
			lastBindingInfo.put("protocol",
					protocolFallback == null || protocolFallback.length() == 0 ? "http"
							: protocolFallback);
			remoteProfile.setLastBindingInfo(lastBindingInfo);
			saveProfile();
		}
	}

	public long getContentPort() {
		return contentPort;
	}

	@Thunk
	boolean open(String protocol, String host, int port) {
		try {

			boolean isLocalHost = "localhost".equals(host);

			try {
				//noinspection ResultOfMethodCallIgnored
				InetAddress.getByName(host);
			} catch (UnknownHostException e) {
				try {
					// Note: {@link org.xbill.DNS.Address#getByName} doesn't handle Netbios names
					host = NbtAddress.getByName(host).getHostAddress();
				} catch (Throwable ignore) {
				}
			}

			rpcRoot = protocol + "://" + host + ":" + port + "/";
			String rpcUrl = rpcRoot + "transmission/rpc";

			if (AndroidUtils.DEBUG) {
				logd("rpc root = " + rpcRoot);
			}

			if (isLocalHost && port == RPC.LOCAL_BIGLYBT_PORT
					&& BiglyCoreUtils.isCoreAllowed()) {
				// wait for Vuze Core to initialize
				// We should be on non-main thread
				// TODO check
				BiglyCoreUtils.waitForCore(currentActivity, 45000);
			}

			if (!host.endsWith(".i2p") && !AndroidUtils.isURLAlive(rpcUrl)) {
				AndroidUtilsUI.showConnectionError(currentActivity,
						remoteProfile.getID(), R.string.error_remote_not_found, false);
				return false;
			}

			AppPreferences appPreferences = BiglyBTApp.getAppPreferences();
			remoteProfile.setLastUsedOn(System.currentTimeMillis());
			appPreferences.setLastRemote(remoteProfile);
			appPreferences.addRemoteProfile(remoteProfile);

			if (host.equals("127.0.0.1") || host.equals("localhost")) {
				baseURL = protocol + "://"
						+ BiglyBTApp.getNetworkState().getActiveIpAddress();
			} else {
				baseURL = protocol + "://" + host;
			}
			setTransmissionRPC(new TransmissionRPC(this, rpcUrl));
			return true;
		} catch (Exception e) {
			if (AndroidUtils.DEBUG) {
				Log.e(TAG, "open", e);
			}
			AnalyticsTracker.getInstance(currentActivity).logError(e);
		}
		return false;
	}

	@Override
	public void sessionPropertiesUpdated(Map<?, ?> map) {
		SessionSettings settings = SessionSettings.createFromRPC(map);

		contentPort = MapUtils.getMapLong(map, "az-content-port", -1);

		mapSupports = MapUtils.getMapMap(map, "supports", Collections.emptyMap());

		remoteProfile.set("lastSessionProperties", map);

		sessionSettings = settings;

		for (SessionSettingsChangedListener l : sessionSettingsChangedListeners) {
			l.sessionSettingsChanged(settings);
		}

		if (!readyForUI) {
			if (getSupports(RPCSupports.SUPPORTS_TAGS)) {
				transmissionRPC.simpleRpcCall("tags-get-list",
						new ReplyMapReceivedListener() {

							@Override
							public void rpcSuccess(String id, Map<?, ?> optionalMap) {
								List<?> tagList = MapUtils.getMapList(optionalMap, "tags",
										null);
								if (tagList == null) {
									tag.mapTags = null;
									setReadyForUI();
									return;
								}

								tag.placeTagListIntoMap(tagList);

								setReadyForUI();
							}

							@Override
							public void rpcFailure(String id, String message) {
								setReadyForUI();
							}

							@Override
							public void rpcError(String id, Exception e) {
								setReadyForUI();
							}
						});
			} else {
				setReadyForUI();
			}
		}

		if (currentActivity != null) {
			String message = MapUtils.getMapString(map, "az-message", null);
			if (message != null && message.length() > 0) {
				AndroidUtilsUI.showDialog(currentActivity,
						R.string.title_message_from_client, R.string.hardcoded_string,
						message);
			}
		}
	}

	public void setCurrentActivity(FragmentActivity currentActivity) {
		this.currentActivity = currentActivity;
		SessionManager.setCurrentVisibleSession(this);
	}

	@Thunk
	void setReadyForUI() {
		readyForUI = true;

		IAnalyticsTracker vet = AnalyticsTracker.getInstance();
		String rpcVersion = transmissionRPC.getRPCVersion() + "/"
				+ transmissionRPC.getRPCVersionAZ();
		vet.setRPCVersion(rpcVersion);
		vet.setClientVersion(transmissionRPC.getClientVersion());

		setupNextRefresh();
		if (torrent.needsFullTorrentRefresh) {
			triggerRefresh(false);
		}
		for (SessionListener l : availabilityListeners) {
			l.sessionReadyForUI(transmissionRPC);
		}
		availabilityListeners.clear();

		synchronized (rpcExecuteList) {
			for (RpcExecuter exec : rpcExecuteList) {
				try {
					exec.executeRpc(transmissionRPC);
				} catch (Throwable t) {
					AnalyticsTracker.getInstance().logError(t);
				}
			}
			rpcExecuteList.clear();
		}
	}

	public boolean isReadyForUI() {
		ensureNotDestroyed();

		return readyForUI;
	}

	/**
	 * Returns a new SessionSettings object
	 */
	public @Nullable SessionSettings getSessionSettingsClone() {
		ensureNotDestroyed();
		if (sessionSettings == null) {
			return null;
		}
		return SessionSettings.createFromRPC(sessionSettings.toRPC(null));
	}

	/**
	 * Allows you to execute an RPC call, ensuring RPC is ready first (may
	 * not be called on same thread)
	 *
	 * @deprecated Discouraged.  Add method to Session that executes the RPC
	 */
	public void executeRpc(RpcExecuter exec) {
		ensureNotDestroyed();

		if (destroyed) {
			if (AndroidUtils.DEBUG) {
				logd("executeRpc ignored, Session destroyed "
						+ AndroidUtils.getCompressedStackTrace());
			}
			return;
		}

		synchronized (rpcExecuteList) {
			if (!readyForUI) {
				rpcExecuteList.add(exec);
				return;
			}
		}

		exec.executeRpc(transmissionRPC);
	}

	void _executeRpc(RpcExecuter exec) {
		ensureNotDestroyed();

		if (destroyed) {
			if (AndroidUtils.DEBUG) {
				logd("_executeRpc ignored, Session destroyed "
						+ AndroidUtils.getCompressedStackTrace());
			}
			return;
		}

		synchronized (rpcExecuteList) {
			if (!readyForUI) {
				rpcExecuteList.add(exec);
				return;
			}
		}

		exec.executeRpc(transmissionRPC);
	}

	/**
	 * @return the remoteProfile
	 */
	public @NonNull RemoteProfile getRemoteProfile() {
		return remoteProfile;
	}

	/**
	 * @param transmissionRPC the rpc to set
	 */
	private void setTransmissionRPC(TransmissionRPC transmissionRPC) {
		if (this.transmissionRPC == transmissionRPC) {
			return;
		}

		if (this.transmissionRPC != null) {
			this.transmissionRPC.removeSessionSettingsReceivedListener(this);
		}

		this.transmissionRPC = transmissionRPC;

		if (transmissionRPC != null) {
			String[] fields;
			if (remoteProfile.isLocalHost()) {
				fields = FILE_FIELDS_LOCALHOST;
			} else {
				if (contentPort <= 0) {
					List<String> strings = new ArrayList<>(
							Arrays.asList(FILE_FIELDS_REMOTE));
					strings.add(TransmissionVars.FIELD_FILES_CONTENT_URL);
					fields = strings.toArray(new String[strings.size()]);
				} else {
					fields = FILE_FIELDS_REMOTE;
				}
			}
			transmissionRPC.setDefaultFileFields(fields);

			transmissionRPC.addTorrentListReceivedListener(
					new TorrentListReceivedListener() {

						@Override
						public void rpcTorrentListReceived(String callID,
								List<?> addedTorrentMaps, List<?> removedTorrentIDs) {

							torrent.lastListReceivedOn = System.currentTimeMillis();
							// XXX If this is a full refresh, we should clear list!
							torrent.addRemoveTorrents(callID, addedTorrentMaps,
									removedTorrentIDs);
						}
					});

			transmissionRPC.addSessionSettingsReceivedListener(this);

		}
	}

	/*
	public HashMap<Object, Map<?, ?>> getTorrentList() {
		synchronized (mLock) {
			return new HashMap<Object, Map<?, ?>>(mapOriginal);
		}
	}
	*/

	/*
	public long[] getTorrentListKeys() {
		synchronized (mLock) {
			int num = mapOriginal.size();
			long[] keys = new long[num];
			for(int i = 0; i < num; i++) {
				keys[i] = mapOriginal.keyAt(i);
			}
			return keys;
		}
	}
	*/

	public void saveProfile() {
		AppPreferences appPreferences = BiglyBTApp.getAppPreferences();
		appPreferences.addRemoteProfile(remoteProfile);
	}

	/**
	 * User specified new settings
	 */
	public void updateSessionSettings(SessionSettings newSettings) {
		if (sessionSettings == null) {
			Log.e(TAG,
					"updateSessionSettings: Can't updateSessionSetting when " + "null");
			return;
		}

		saveProfile();

		setupNextRefresh();

		Map<String, Object> changes = newSettings.toRPC(sessionSettings);

		if (changes.size() > 0) {
			transmissionRPC.updateSettings(changes);
		}

		sessionSettings = newSettings;

		triggerSessionSettingsChanged();
	}

	public void triggerSessionSettingsChanged() {
		for (SessionSettingsChangedListener l : sessionSettingsChangedListeners) {
			l.sessionSettingsChanged(sessionSettings);
		}
	}

	@Thunk
	void cancelRefreshHandler() {
		if (handler == null) {
			return;
		}
		handler.removeCallbacksAndMessages(null);
		handler = null;
	}

	protected void setupNextRefresh() {
		if (AndroidUtils.DEBUG_ANNOY) {
			logd("setupNextRefresh");
		}
		long interval = remoteProfile.calcUpdateInterval();
		if (handler != null && interval == lastRefreshInterval) {
			return;
		}
		lastRefreshInterval = interval;
		if (AndroidUtils.DEBUG_ANNOY) {
			logd("Handler fires every " + interval);
		}
		if (interval <= 0) {
			cancelRefreshHandler();
			return;
		}
		handler = new Handler(Looper.getMainLooper());
		handler.postDelayed(handlerRunnable, interval * 1000);
	}

	public void triggerRefresh(final boolean recentOnly) {
		if (transmissionRPC == null) {
			return;
		}
		if (!readyForUI) {
			if (AndroidUtils.DEBUG) {
				logd("trigger refresh called before Session-Get for "
						+ AndroidUtils.getCompressedStackTrace());
			}
			return;
		}
		synchronized (mLock) {
			if (torrent.isRefreshingList()) {
				if (AndroidUtils.DEBUG) {
					logd("Refresh skipped. Already refreshing");
				}
				return;
			}
			torrent.setRefreshingList(true);
		}
		if (AndroidUtils.DEBUG_ANNOY) {
			logd("Refresh Triggered " + AndroidUtils.getCompressedStackTrace());
		}

		if (tag.needsTagRefresh) {
			tag.refreshTags(false);
		}

		transmissionRPC.getSessionStats(SESSION_STATS_FIELDS,
				new ReplyMapReceivedListener() {
					@Override
					public void rpcSuccess(String id, Map<?, ?> optionalMap) {
						updateSessionStats(optionalMap);

						TorrentListReceivedListener listener = new TorrentListReceivedListener() {

							@Override
							public void rpcTorrentListReceived(String callID,
									List<?> addedTorrentMaps, List<?> removedTorrentIDs) {
								torrent.setRefreshingList(false);
							}
						};

						if (recentOnly && !torrent.needsFullTorrentRefresh) {
							transmissionRPC.getRecentTorrents(TAG, listener);
						} else {
							transmissionRPC.getAllTorrents(TAG, listener);
							torrent.needsFullTorrentRefresh = false;
						}
					}

					@Override
					public void rpcError(String id, Exception e) {
						torrent.setRefreshingList(false);
					}

					@Override
					public void rpcFailure(String id, String message) {
						torrent.setRefreshingList(false);
					}
				});
	}

	@Thunk
	void updateSessionStats(Map<?, ?> map) {
		Map<?, ?> oldSessionStats = mapSessionStats;
		mapSessionStats = map;

//	 string                     | value type
//
// ---------------------------+-------------------------------------------------
//   "activeTorrentCount"       | number
//   "downloadSpeed"            | number
//   "pausedTorrentCount"       | number
//   "torrentCount"             | number
//   "uploadSpeed"              | number
//   ---------------------------+-------------------------------+
//   "cumulative-stats"         | object, containing:           |
//		                          +------------------+------------+
//		                          | uploadedBytes    | number     |
// tr_session_stats
//		                          | downloadedBytes  | number     |
// tr_session_stats
//		                          | filesAdded       | number     |
// tr_session_stats
//		                          | sessionCount     | number     |
// tr_session_stats
//		                          | secondsActive    | number     |
// tr_session_stats
//   ---------------------------+-------------------------------+
//   "current-stats"            | object, containing:           |
//                              +------------------+------------+
//                              | uploadedBytes    | number     |
// tr_session_stats
//                              | downloadedBytes  | number     |
// tr_session_stats
//                              | filesAdded       | number     |
// tr_session_stats
//                              | sessionCount     | number     |
// tr_session_stats
//                              | secondsActive    | number     |
// tr_session_stats

		long oldDownloadSpeed = MapUtils.getMapLong(oldSessionStats,
				TransmissionVars.TR_SESSION_STATS_DOWNLOAD_SPEED, 0);
		long newDownloadSpeed = MapUtils.getMapLong(map,
				TransmissionVars.TR_SESSION_STATS_DOWNLOAD_SPEED, 0);
		long oldUploadSpeed = MapUtils.getMapLong(oldSessionStats,
				TransmissionVars.TR_SESSION_STATS_UPLOAD_SPEED, 0);
		long newUploadSpeed = MapUtils.getMapLong(map,
				TransmissionVars.TR_SESSION_STATS_UPLOAD_SPEED, 0);

		if (oldDownloadSpeed != newDownloadSpeed
				|| oldUploadSpeed != newUploadSpeed) {
			for (SessionSettingsChangedListener l : sessionSettingsChangedListeners) {
				l.speedChanged(newDownloadSpeed, newUploadSpeed);
			}
		}
	}

	public void addSessionSettingsChangedListeners(
			SessionSettingsChangedListener l) {
		ensureNotDestroyed();

		synchronized (sessionSettingsChangedListeners) {
			if (!sessionSettingsChangedListeners.contains(l)) {
				sessionSettingsChangedListeners.add(l);
				if (sessionSettings != null) {
					l.sessionSettingsChanged(sessionSettings);
				}
			}
		}
	}

	public void removeSessionSettingsChangedListeners(
			SessionSettingsChangedListener l) {
		synchronized (sessionSettingsChangedListeners) {
			sessionSettingsChangedListeners.remove(l);
		}
	}

	/**
	 * add SessionListener.  listener is only triggered once for each method,
	 * and then removed
	 */
	public void addSessionListener(SessionListener l) {
		ensureNotDestroyed();

		if (readyForUI && transmissionRPC != null) {
			l.sessionReadyForUI(transmissionRPC);
		} else {
			synchronized (availabilityListeners) {
				if (availabilityListeners.contains(l)) {
					return;
				}
				availabilityListeners.add(l);
			}
		}
	}

	public void addRefreshTriggerListener(RefreshTriggerListener l) {
		ensureNotDestroyed();

		if (refreshTriggerListeners.contains(l)) {
			return;
		}
		l.triggerRefresh();
		refreshTriggerListeners.add(l);
	}

	public void removeRefreshTriggerListener(RefreshTriggerListener l) {
		refreshTriggerListeners.remove(l);
	}

	@Override
	public void onlineStateChanged(boolean isOnline, boolean isOnlineMobile) {
		ensureNotDestroyed();

		if (!readyForUI) {
			return;
		}
		setupNextRefresh();
	}

	@Thunk
	boolean isActivityVisible() {
		ensureNotDestroyed();
		if (activityVisible
				&& (currentActivity == null || currentActivity.isFinishing())) {
			activityVisible = false;
			if (SessionManager.getCurrentVisibleSession() == this) {
				SessionManager.setCurrentVisibleSession(null);
			}
		}
		return activityVisible;
	}

	public void activityResumed(FragmentActivity currentActivity) {
		ensureNotDestroyed();

		if (AndroidUtils.DEBUG) {
			logd("ActivityResumed. needsFullTorrentRefresh? "
					+ torrent.needsFullTorrentRefresh);
		}
		this.currentActivity = currentActivity;
		SessionManager.setCurrentVisibleSession(this);
		activityVisible = true;
		if (torrent.needsFullTorrentRefresh) {
			triggerRefresh(false);
		} else {
			if (remoteProfile.getRemoteType() == RemoteProfile.TYPE_CORE) {
				new Thread(new Runnable() {
					@Override
					public void run() {
						BiglyCoreUtils.waitForCore(Session.this.currentActivity, 20000);
						triggerRefresh(false);
					}
				}).start();
			}

			setupNextRefresh();
		}
	}

	public void activityLostForeground(Activity currentActivity) {
		if (this.currentActivity != null && !this.currentActivity.isFinishing()) {
			ensureNotDestroyed();
		}

		// Another activities Resume might be called before Pause
		if (this.currentActivity == currentActivity) {
			SessionManager.setCurrentVisibleSession(null);
			activityVisible = false;
		}
	}

	public void activityStop(Activity activity) {
		if (this.currentActivity == activity) {
			this.currentActivity = null;
			SessionManager.setCurrentVisibleSession(null);
			activityVisible = false;
		}
	}

	/**
	 * @return -1 == Not Vuze; 0 == Vuze
	 */
	public int getRPCVersionAZ() {
		ensureNotDestroyed();
		return transmissionRPC == null ? -1 : transmissionRPC.getRPCVersionAZ();
	}

	public boolean getSupports(String id) {
		return MapUtils.getMapBoolean(mapSupports, id, false);
	}

	public String getBaseURL() {
		ensureNotDestroyed();
		return baseURL;
	}

	@Thunk
	static void showUrlFailedDialog(final FragmentActivity activity,
			final String errMsg, final String url, final String sample) {
		if (activity == null) {
			Log.e(null, "No activity for error message " + errMsg);
			return;
		}
		activity.runOnUiThread(new Runnable() {
			public void run() {
				if (activity.isFinishing()) {
					return;
				}
				String s = activity.getResources().getString(
						R.string.torrent_url_add_failed, url, sample);

				Spanned msg = AndroidUtils.fromHTML(s);
				AlertDialog.Builder builder = new AlertDialog.Builder(
						activity).setMessage(msg).setCancelable(true).setNegativeButton(
								android.R.string.ok, new DialogInterface.OnClickListener() {
									public void onClick(DialogInterface dialog, int which) {
									}
								}).setNeutralButton(R.string.torrent_url_add_failed_openurl,
										new OnClickListener() {
											@Override
											public void onClick(DialogInterface dialog, int which) {
												AndroidUtilsUI.openURL(activity, url, url);
											}
										});
				builder.show();
			}
		});

	}

	public void moveDataHistoryChanged(ArrayList<String> history) {
		ensureNotDestroyed();

		if (remoteProfile == null) {
			return;
		}
		remoteProfile.setSavePathHistory(history);
		saveProfile();
	}

	public FragmentActivity getCurrentActivity() {
		ensureNotDestroyed();

		return currentActivity;
	}

	void ensureNotDestroyed() {
		if (destroyed) {
			Log.e(TAG, "Accessing destroyed Session"
					+ AndroidUtils.getCompressedStackTrace());
		}
	}

	public boolean isDestroyed() {
		return destroyed;
	}

	protected void destroy() {
		if (AndroidUtils.DEBUG) {
			logd("destroy: " + AndroidUtils.getCompressedStackTrace());
		}
		cancelRefreshHandler();
		if (transmissionRPC != null) {
			transmissionRPC.destroy();
		}
		torrent.clearCache();
		torrent.clearFilesCaches(false);
		availabilityListeners.clear();
		refreshTriggerListeners.clear();
		sessionSettingsChangedListeners.clear();
		subscription.destroy();
		tag.destroy();
		torrent.destroy();
		currentActivity = null;
		BiglyBTApp.getNetworkState().removeListener(this);

		destroyed = true;
		boolean isCore = remoteProfile.getRemoteType() == RemoteProfile.TYPE_CORE;

		if (isCore) {
			BiglyCoreUtils.detachCore();
		}
	}

	public void logd(String s) {
		Log.d(TAG, remoteProfile.getNick() + "] " + s);
	}
}
