import { initializeApp } from 'firebase/app';
import { getAuth, onAuthStateChanged, signInWithEmailAndPassword, createUserWithEmailAndPassword, signOut, GoogleAuthProvider, signInWithPopup, signInAnonymously } from 'firebase/auth';
import { getFirestore, doc, setDoc, onSnapshot, collection, query, where, limit, updateDoc, serverTimestamp } from 'firebase/firestore';
import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';

// Firebase configuration placeholder. 
// Replace with your actual Firebase project config.
const firebaseConfig = {
  apiKey: "AIzaSyAHlNM7GndK0ea69MFXSILpBtyuh_2q5UE",
  authDomain: "janus-sync-bridge-5a2.firebaseapp.com",
  projectId: "janus-sync-bridge-5a2",
  storageBucket: "janus-sync-bridge-5a2.firebasestorage.app",
  messagingSenderId: "891842716890",
  appId: "1:891842716890:web:6d2f8c03f95bfc63eab1e3"
};

// Initialize Firebase
const app = initializeApp(firebaseConfig);

export const auth = getAuth(app);
export const db = getFirestore(app);
export const googleProvider = new GoogleAuthProvider();

export const signInWithGoogle = async () => {
  return await signInWithPopup(auth, googleProvider);
};

let currentUid: string | null = null;
let currentDeviceId = "";
let clipboardUnsubscribe: (() => void) | null = null;
let notificationsUnsubscribe: (() => void) | null = null;
let presenceInterval: any = null;

// Unique Device ID for Desktop
const getDeviceId = () => {
  if (!currentDeviceId) {
    let id = localStorage.getItem('janus_device_id');
    if (!id) {
      id = crypto.randomUUID();
      localStorage.setItem('janus_device_id', id);
    }
    currentDeviceId = id;
  }
  return currentDeviceId;
};

// Start syncing presence, clipboard and notifications
export const startCloudSync = (uid: string) => {
  currentUid = uid;
  const devId = getDeviceId();

  // 1. Maintain Presence and Local IP in Firestore
  const updatePresence = async () => {
    try {
      const identity = await invoke<any>('get_identity').catch(() => ({ ip: '127.0.0.1', name: 'Janus macOS' }));
      const bestIp = identity.ip;
      const hostname = identity.name;
      
      const devDoc = doc(db, 'users', uid, 'devices', devId);
      await setDoc(devDoc, {
        deviceId: devId,
        name: hostname,
        type: 'macos',
        localIp: bestIp,
        port: 53317,
        status: 'online',
        lastActive: serverTimestamp()
      }, { merge: true });
    } catch (e) {
      console.error('Failed to update presence in Firestore:', e);
    }
  };

  updatePresence();
  presenceInterval = setInterval(updatePresence, 30000);

  // 2. Listen to Firestore Clipboard changes
  const clipboardDoc = doc(db, 'users', uid, 'clipboard', 'current');
  clipboardUnsubscribe = onSnapshot(clipboardDoc, async (snapshot) => {
    if (!snapshot.exists()) return;
    const data = snapshot.data();
    if (data.senderId !== devId && data.content) {
      console.log('Received clipboard update from Firestore:', data.content.substring(0, 50));
      try {
        await invoke('write_clipboard', { content: data.content });
      } catch (e) {
        console.error('Failed to write remote clipboard to system:', e);
      }
    }
  });

  // 3. Listen to Firestore Notification updates
  const notificationsCol = collection(db, 'users', uid, 'notifications');
  const notificationsQuery = query(notificationsCol, where('isDismissed', '==', false), limit(10));
  notificationsUnsubscribe = onSnapshot(notificationsQuery, (snapshot) => {
    snapshot.docChanges().forEach(async (change) => {
      if (change.type === 'added') {
        const notif = change.doc.data();
        if (notif.title || notif.text) {
          console.log('Received notification from Firestore:', notif);
          try {
            await invoke('show_notification', {
              title: `${notif.appName}: ${notif.title || ''}`,
              body: notif.text || ''
            });
          } catch (e) {
            console.error('Failed to display native macOS notification:', e);
          }
        }
      }
    });
  });
};

// Stop syncing presence and unsubscribe from listeners
export const stopCloudSync = async () => {
  if (currentUid && currentDeviceId) {
    // Mark offline
    const devDoc = doc(db, 'users', currentUid, 'devices', currentDeviceId);
    await updateDoc(devDoc, { status: 'offline' }).catch(() => {});
  }
  
  if (presenceInterval) {
    clearInterval(presenceInterval);
    presenceInterval = null;
  }
  if (clipboardUnsubscribe) {
    clipboardUnsubscribe();
    clipboardUnsubscribe = null;
  }
  if (notificationsUnsubscribe) {
    notificationsUnsubscribe();
    notificationsUnsubscribe = null;
  }
  currentUid = null;
};

// Monitor Local Clipboard changes via Tauri event "clipboard-synced"
listen('clipboard-synced', (event: any) => {
  const payload = event.payload;
  // Only upload if it originated locally
  if (payload.source === 'local' && currentUid) {
    const devId = getDeviceId();
    const clipboardDoc = doc(db, 'users', currentUid, 'clipboard', 'current');
    setDoc(clipboardDoc, {
      content: payload.content,
      contentType: payload.content_type,
      senderId: devId,
      timestamp: serverTimestamp()
    }).then(() => {
      console.log('Uploaded local clipboard to Firestore successfully');
    }).catch(e => {
      console.error('Failed to upload clipboard to Firestore:', e);
    });
  }
});

export const signInInstantCloud = async () => {
  return await signInAnonymously(auth);
};
