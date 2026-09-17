package com.example.opennanoor.service

import android.service.notification.NotificationListenerService

/**
 * Exists only to give MediaSessionManager.getActiveSessions() a component
 * to authorize against - notification listener access is what that API
 * gates on, even for an app that only wants media sessions and has no
 * interest in reading actual notification content. Deliberately does
 * nothing with the notifications it's handed.
 */
class MediaListenerService : NotificationListenerService()
