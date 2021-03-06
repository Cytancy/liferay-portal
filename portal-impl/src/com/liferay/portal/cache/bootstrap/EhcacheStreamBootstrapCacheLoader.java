/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.portal.cache.bootstrap;

import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.InitialThreadLocal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.bootstrap.BootstrapCacheLoader;

/**
 * @author Shuyang Zhou
 * @author Sherry Yang
 */
public class EhcacheStreamBootstrapCacheLoader implements BootstrapCacheLoader {

	public static void resetSkip() {
		_skipBootstrapThreadLocal.remove();
	}

	public static void setSkip() {
		_skipBootstrapThreadLocal.set(Boolean.TRUE);
	}

	public static synchronized void start() {
		if (!_started) {
			_started = true;
		}

		if (_deferredEhcaches.isEmpty()) {
			return;
		}

		if (_log.isDebugEnabled()) {
			_log.debug("Loading deferred caches");
		}

		try {
			for (String cacheManagerName : _deferredEhcaches.keySet()) {
				List<String> cacheNames = _deferredEhcaches.get(
					cacheManagerName);

				if (cacheNames.isEmpty()) {
					continue;
				}

				StreamBootstrapHelpUtil.loadCachesFromCluster(
					cacheManagerName,
					cacheNames.toArray(new String[cacheNames.size()]));
			}
		}
		catch (Exception e) {
			if (_log.isWarnEnabled()) {
				_log.warn("Unable to load cache data from the cluster", e);
			}
		}
		finally {
			_deferredEhcaches.clear();
		}
	}

	public EhcacheStreamBootstrapCacheLoader(Properties properties) {
		if (properties != null) {
			_bootstrapAsynchronously = GetterUtil.getBoolean(
				properties.getProperty("bootstrapAsynchronously"));
		}
	}

	@Override
	public Object clone() {
		return this;
	}

	public void doLoad(Ehcache ehcache) {
		synchronized (EhcacheStreamBootstrapCacheLoader.class) {
			if (!_started) {
				CacheManager cacheManager = ehcache.getCacheManager();

				String cacheManagerName = cacheManager.getName();

				List<String> cacheNames = _deferredEhcaches.get(
					cacheManagerName);

				if (cacheNames == null) {
					cacheNames = new ArrayList<String>();

					_deferredEhcaches.put(cacheManagerName, cacheNames);
				}

				cacheNames.add(ehcache.getName());

				return;
			}
		}

		if (_skipBootstrapThreadLocal.get()) {
			return;
		}

		if (_log.isDebugEnabled()) {
			_log.debug("Bootstraping " + ehcache.getName());
		}

		CacheManager cacheManager = ehcache.getCacheManager();

		try {
			StreamBootstrapHelpUtil.loadCachesFromCluster(
				cacheManager.getName(), ehcache.getName());
		}
		catch (Exception e) {
			if (_log.isWarnEnabled()) {
				_log.warn("Unable to load cache data from the cluster", e);
			}
		}
	}

	@Override
	public boolean isAsynchronous() {
		return _bootstrapAsynchronously;
	}

	@Override
	public void load(Ehcache ehcache) {
		if (_bootstrapAsynchronously) {
			EhcacheStreamClientThread streamBootstrapThread =
				new EhcacheStreamClientThread(ehcache);

			streamBootstrapThread.start();
		}
		else {
			doLoad(ehcache);
		}
	}

	private static Log _log = LogFactoryUtil.getLog(
		EhcacheStreamBootstrapCacheLoader.class);

	private static Map<String, List<String>> _deferredEhcaches =
		new HashMap<String, List<String>>();
	private static ThreadLocal<Boolean> _skipBootstrapThreadLocal =
		new InitialThreadLocal<Boolean>(
			EhcacheStreamBootstrapCacheLoader.class +
				"._skipBootstrapThreadLocal",
			false);
	private static boolean _started;

	private boolean _bootstrapAsynchronously = true;

	private class EhcacheStreamClientThread extends Thread {

		public EhcacheStreamClientThread(Ehcache ehcache) {
			if (_log.isDebugEnabled()) {
				_log.debug(
					"Ehcache stream client thread for cache " +
						ehcache.getName());
			}

			_ehcache = ehcache;

			setDaemon(true);
			setName(
				EhcacheStreamClientThread.class.getName() + " - " +
					ehcache.getName());
			setPriority(Thread.NORM_PRIORITY);
		}

		@Override
		public void run() {
			try {
				doLoad(_ehcache);
			}
			catch (Exception e) {
				if (_log.isWarnEnabled()) {
					_log.warn("Unable to asynchronously stream bootstrap", e);
				}
			}
		}

		private Ehcache _ehcache;

	}

}