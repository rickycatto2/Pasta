/* Pasta — client-side clipboard board. */
(() => {
  'use strict';

  const MAX_TEXT = 100000;
  const MAX_IMAGE = 20 * 1024 * 1024;
  const PAGE_SIZE = 60;
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

  const state = {
    me: null,
    clips: [],
    stats: null,
    loading: true,
    loadingMore: false,
    error: null,
    hasMore: false,
    nextBefore: null,
    filter: 'all',
    search: '',
    mode: 'text',
    device: loadDevice(),
    sending: false,
    uploading: false,
    loadSeq: 0,
    live: false,
    hasConnected: false,
    newIds: new Set(),
    expanded: new Set(),
    lightboxClip: null,
    confirmHandler: null,
    unread: 0,
    installPrompt: null
  };

  const els = {
    loginView: $('#login-view'),
    appView: $('#app-view'),
    loginForm: $('#login-form'),
    password: $('#password'),
    togglePassword: $('#toggle-password'),
    loginError: $('#login-error'),
    loginSubmit: $('#login-submit'),
    loginStatus: $('#login-status'),
    loginStatusDot: $('#login-status-dot'),
    loginHost: $('#login-host'),
    livePill: $('#live-pill'),
    liveText: $('#live-text'),
    pasteClipboardBtn: $('#paste-clipboard-btn'),
    settingsBtn: $('#settings-btn'),
    footerSettings: $('#footer-settings'),
    composer: $('.composer'),
    composerInput: $('#composer-input'),
    tabs: $$('.tab'),
    textPane: $('#text-pane'),
    imagePane: $('#image-pane'),
    imageDrop: $('#image-drop'),
    fileInput: $('#file-input'),
    attachBtn: $('#attach-btn'),
    sendBtn: $('#send-btn'),
    charCount: $('#char-count'),
    deviceLabel: $('#device-label'),
    clipCount: $('#clip-count'),
    statSize: $('#stat-size'),
    feed: $('#feed'),
    feedCount: $('#feed-count'),
    loadMoreWrap: $('#load-more-wrap'),
    loadMore: $('#load-more'),
    filters: $$('.filter'),
    searchInput: $('#search-input'),
    clearSearch: $('#clear-search'),
    refreshBtn: $('#refresh-btn'),
    dropOverlay: $('#drop-overlay'),
    settingsDialog: $('#settings-dialog'),
    deviceNameInput: $('#device-name-input'),
    saveDeviceBtn: $('#save-device-btn'),
    sessionList: $('#session-list'),
    storageClips: $('#storage-clips'),
    storageImages: $('#storage-images'),
    storageSize: $('#storage-size'),
    settingsVersion: $('#settings-version'),
    signoutBtn: $('#signout-btn'),
    androidDownload: $('#android-download'),
    copyAppLink: $('#copy-app-link'),
    lightbox: $('#lightbox'),
    lightboxImg: $('#lightbox-img'),
    lightboxName: $('#lightbox-name'),
    lightboxCopy: $('#lightbox-copy'),
    lightboxDownload: $('#lightbox-download'),
    confirmDialog: $('#confirm-dialog'),
    confirmEyebrow: $('#confirm-eyebrow'),
    confirmTitle: $('#confirm-title'),
    confirmCopy: $('#confirm-copy'),
    confirmCancel: $('#confirm-cancel'),
    confirmOk: $('#confirm-ok'),
    toastRegion: $('#toast-region')
  };

  const ICON = {
    copy: '#i-copy',
    check: '#i-check',
    pin: '#i-pin',
    trash: '#i-trash',
    image: '#i-image',
    text: '#i-text',
    search: '#i-search',
    close: '#i-close',
    refresh: '#i-refresh',
    cloud: '#i-cloud',
    clipboard: '#i-clipboard',
    android: '#i-android',
    lock: '#i-lock',
    download: '#i-download',
    arrow: '#i-arrow',
    spark: '#i-spark',
    shield: '#i-shield'
  };

  // ---------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------

  function icon(name, className = 'icon') {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', className);
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', ICON[name] || name);
    svg.append(use);
    return svg;
  }

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  }

  function loadDevice() {
    try {
      const saved = localStorage.getItem('pasta.device');
      if (saved) return saved;
    } catch {}
    const ua = navigator.userAgent || '';
    let browser = 'Browser';
    if (/Edg\//.test(ua)) browser = 'Edge';
    else if (/OPR\//.test(ua)) browser = 'Opera';
    else if (/Firefox\//.test(ua)) browser = 'Firefox';
    else if (/Chrome\//.test(ua) || /CriOS/.test(ua)) browser = 'Chrome';
    else if (/Safari\//.test(ua)) browser = 'Safari';
    let platform = 'Device';
    if (/iPhone/.test(ua)) platform = 'iPhone';
    else if (/iPad/.test(ua)) platform = 'iPad';
    else if (/Android/.test(ua)) platform = 'Android';
    else if (/Windows/.test(ua)) platform = 'Windows';
    else if (/Macintosh|Mac OS X/.test(ua)) platform = 'Mac';
    else if (/Linux/.test(ua)) platform = 'Linux';
    return `${browser} · ${platform}`;
  }

  function updateDeviceLabel() {
    if (els.deviceLabel) els.deviceLabel.textContent = state.device;
  }

  function formatBytes(bytes) {
    const value = Number(bytes) || 0;
    if (value < 1024) return `${value} B`;
    if (value < 1024 * 1024) return `${(value / 1024).toFixed(value < 10 * 1024 ? 1 : 0)} KB`;
    if (value < 1024 * 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(value < 10 * 1024 * 1024 ? 1 : 0)} MB`;
    return `${(value / (1024 * 1024 * 1024)).toFixed(1)} GB`;
  }

  function relativeTime(timestamp) {
    const delta = Math.max(0, Date.now() - Number(timestamp));
    if (delta < 45_000) return 'just now';
    if (delta < 3_600_000) return `${Math.floor(delta / 60_000)}m`;
    if (delta < 86_400_000) return `${Math.floor(delta / 3_600_000)}h`;
    if (delta < 7 * 86_400_000) return `${Math.floor(delta / 86_400_000)}d`;
    return new Date(timestamp).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  }

  function absoluteTime(timestamp) {
    return new Date(timestamp).toLocaleString(undefined, {
      weekday: 'short',
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit'
    });
  }

  function dayKey(timestamp) {
    const date = new Date(timestamp);
    return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
  }

  function dayLabel(timestamp) {
    const date = new Date(timestamp);
    const today = new Date();
    const startToday = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime();
    const startDate = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
    const days = Math.round((startToday - startDate) / 86_400_000);
    if (days === 0) return 'Today';
    if (days === 1) return 'Yesterday';
    if (days > 1 && days < 7) return date.toLocaleDateString(undefined, { weekday: 'long' });
    return date.toLocaleDateString(undefined, {
      month: 'long',
      day: 'numeric',
      ...(date.getFullYear() !== today.getFullYear() ? { year: 'numeric' } : {})
    });
  }

  function plural(count, singular, pluralForm = `${singular}s`) {
    return `${count.toLocaleString()} ${count === 1 ? singular : pluralForm}`;
  }

  function flashButton(button, copied = true) {
    if (!button) return;
    const span = button.querySelector('span');
    const oldLabel = span ? span.textContent : null;
    const use = button.querySelector('use');
    const oldHref = use ? use.getAttribute('href') : null;
    button.classList.add('is-copied');
    if (span) span.textContent = copied ? 'Copied' : oldLabel;
    if (use) use.setAttribute('href', ICON.check);
    window.setTimeout(() => {
      button.classList.remove('is-copied');
      if (span && oldLabel !== null) span.textContent = oldLabel;
      if (use && oldHref) use.setAttribute('href', oldHref);
    }, 1600);
  }

  function toast(message, type = 'ok') {
    if (!message) return;
    const item = el('div', `toast${type === 'error' ? ' is-error' : ''}`);
    const iconWrap = el('span', 'toast-icon');
    iconWrap.append(icon(type === 'error' ? 'close' : 'check'));
    item.append(iconWrap, el('span', 'toast-text', message));
    els.toastRegion.append(item);
    while (els.toastRegion.children.length > 3) els.toastRegion.firstElementChild.remove();
    window.setTimeout(() => {
      item.classList.add('is-leaving');
      window.setTimeout(() => item.remove(), 200);
    }, type === 'error' ? 4200 : 3000);
  }

  function setLive(online) {
    state.live = online;
    if (!els.livePill) return;
    els.livePill.classList.toggle('is-offline', !online);
    els.liveText.textContent = online ? 'Live' : 'Reconnecting';
  }

  function handleSignedOut() {
    if (!state.me && els.appView.hidden) return;
    closeEvents();
    state.me = null;
    state.clips = [];
    state.stats = null;
    state.loading = false;
    els.appView.hidden = true;
    els.loginView.hidden = false;
    document.title = 'Pasta — one clipboard, everywhere';
    checkHealth();
    if (window.matchMedia('(pointer: fine)').matches) {
      window.setTimeout(() => els.password.focus(), 80);
    }
  }

  // ---------------------------------------------------------------------
  // API
  // ---------------------------------------------------------------------

  class ApiError extends Error {
    constructor(message, status) {
      super(message);
      this.status = status;
    }
  }

  async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    let body = options.body;
    if (body !== undefined && !(body instanceof Blob) && !(body instanceof FormData) && !(body instanceof ArrayBuffer)) {
      headers.set('Content-Type', 'application/json');
      body = JSON.stringify(body);
    }
    if ((options.method || 'GET') !== 'GET') headers.set('X-Pasta', '1');
    const response = await fetch(path, {
      ...options,
      body,
      headers,
      credentials: 'same-origin',
      cache: 'no-store'
    });
    let data = null;
    const contentType = response.headers.get('content-type') || '';
    if (contentType.includes('application/json')) {
      data = await response.json().catch(() => null);
    } else {
      data = await response.text().catch(() => '');
    }
    if (!response.ok) {
      const message = data && data.error ? data.error : `Request failed (${response.status}).`;
      if (response.status === 401 && !options.allow401) handleSignedOut();
      throw new ApiError(message, response.status);
    }
    return data;
  }

  // ---------------------------------------------------------------------
  // Login
  // ---------------------------------------------------------------------

  async function checkHealth() {
    els.loginHost.textContent = location.host || 'pasta';
    try {
      const controller = new AbortController();
      const timer = window.setTimeout(() => controller.abort(), 5000);
      const response = await fetch('/api/health', { cache: 'no-store', signal: controller.signal });
      window.clearTimeout(timer);
      if (!response.ok) throw new Error('unhealthy');
      await response.json();
      els.loginStatus.textContent = 'Private tunnel online';
      els.loginStatusDot.className = 'live-dot is-online';
    } catch {
      els.loginStatus.textContent = 'Cannot reach the Pasta server';
      els.loginStatusDot.className = 'live-dot is-offline';
    }
  }

  async function checkAuth() {
    try {
      const response = await fetch('/api/auth/me', {
        headers: { Accept: 'application/json' },
        cache: 'no-store',
        credentials: 'same-origin'
      });
      if (response.ok) {
        const data = await response.json();
        if (data.authenticated) {
          state.me = data.session;
          enterApp();
          return;
        }
      }
    } catch {}
    showLogin();
  }

  function showLogin() {
    closeEvents();
    for (const dialog of $$('dialog[open]')) {
      try { dialog.close(); } catch {}
    }
    state.loading = true;
    state.error = null;
    els.appView.hidden = true;
    els.loginView.hidden = false;
    els.loginError.hidden = true;
    els.password.value = '';
    document.title = 'Pasta — one clipboard, everywhere';
    checkHealth();
    if (window.matchMedia('(pointer: fine)').matches) {
      window.setTimeout(() => els.password.focus(), 100);
    }
  }

  function setLoginBusy(busy) {
    els.loginSubmit.classList.toggle('is-busy', busy);
    els.loginSubmit.disabled = busy;
    const span = els.loginSubmit.querySelector('span');
    if (span) span.textContent = busy ? 'Unlocking…' : 'Unlock clipboard';
  }

  async function submitLogin(event) {
    event.preventDefault();
    if (els.loginSubmit.disabled) return;
    const password = els.password.value;
    els.loginError.hidden = true;
    if (!password) {
      els.loginError.textContent = 'Enter your passphrase first.';
      els.loginError.hidden = false;
      els.password.focus();
      return;
    }
    setLoginBusy(true);
    try {
      const response = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ password, label: state.device })
      });
      const data = await response.json().catch(() => null);
      if (!response.ok) {
        throw new ApiError(data && data.error ? data.error : 'Could not unlock the clipboard.', response.status);
      }
      state.me = data.session;
      els.password.value = '';
      enterApp();
    } catch (error) {
      els.loginError.textContent = error instanceof TypeError
        ? 'Could not reach the server. Check your connection and try again.'
        : error.message;
      els.loginError.hidden = false;
      els.password.focus();
      els.password.select();
    } finally {
      setLoginBusy(false);
    }
  }

  function togglePasswordVisibility() {
    const showing = els.password.type === 'text';
    els.password.type = showing ? 'password' : 'text';
    els.togglePassword.setAttribute('aria-label', showing ? 'Show passphrase' : 'Hide passphrase');
    els.togglePassword.title = showing ? 'Show passphrase' : 'Hide passphrase';
    const use = els.togglePassword.querySelector('use');
    if (use) use.setAttribute('href', showing ? '#i-eye' : '#i-eye-off');
    els.password.focus();
  }

  function enterApp() {
    els.loginView.hidden = true;
    els.appView.hidden = false;
    state.unread = 0;
    document.title = 'Pasta';
    updateDeviceLabel();
    if (state.me) {
      state.device = state.device || state.me.label;
    }
    refreshAll();
    connectEvents();
  }

  async function doLogout() {
    try {
      await api('/api/auth/logout', { method: 'POST' });
    } catch {}
    closeEvents();
    state.me = null;
    state.clips = [];
    state.stats = null;
    els.settingsDialog.close();
    showLogin();
    toast('Signed out of this device.');
  }

  // ---------------------------------------------------------------------
  // Loading
  // ---------------------------------------------------------------------

  function upsertClip(clip) {
    if (!clip || !clip.id) return;
    const index = state.clips.findIndex((item) => item.id === clip.id);
    if (index >= 0) state.clips[index] = clip;
    else state.clips.push(clip);
    state.clips.sort((a, b) => b.createdAt - a.createdAt);
  }

  function markNew(id) {
    if (!id) return;
    state.newIds.add(id);
    window.setTimeout(() => {
      state.newIds.delete(id);
      const card = els.feed.querySelector(`[data-id="${id}"]`);
      if (card) card.classList.remove('is-new');
    }, 750);
  }

  async function loadClips(reset = false) {
    if (state.loadingMore) return;
    const seq = ++state.loadSeq;
    if (reset) {
      state.loading = true;
      state.error = null;
    } else {
      if (!state.nextBefore) return;
      state.loadingMore = true;
      els.loadMore.disabled = true;
      els.loadMore.textContent = 'Loading…';
    }
    renderFeed();

    const params = new URLSearchParams({ limit: String(PAGE_SIZE) });
    if (!reset && state.nextBefore) params.set('before', String(state.nextBefore));
    if (state.search) params.set('q', state.search);

    try {
      const data = await api(`/api/clips?${params.toString()}`);
      if (seq !== state.loadSeq) return;
      if (reset) state.clips = data.clips || [];
      else {
        for (const clip of data.clips || []) {
          if (!state.clips.some((item) => item.id === clip.id)) state.clips.push(clip);
        }
        state.clips.sort((a, b) => b.createdAt - a.createdAt);
      }
      state.hasMore = Boolean(data.hasMore);
      state.nextBefore = data.nextBefore || null;
      state.error = null;
    } catch (error) {
      if (seq === state.loadSeq && error.status !== 401) {
        state.error = error.message || 'Could not load your clipboard.';
      }
    } finally {
      if (seq === state.loadSeq) {
        state.loading = false;
        state.loadingMore = false;
        els.loadMore.disabled = false;
        els.loadMore.textContent = 'Load older pastes';
        renderFeed();
      }
    }
  }

  async function refreshStats() {
    try {
      state.stats = await api('/api/stats');
      renderStats();
    } catch {}
  }

  function renderStats() {
    const stats = state.stats;
    if (!stats) return;
    els.clipCount.textContent = Number(stats.clips || 0).toLocaleString();
    els.statSize.textContent = formatBytes(stats.bytes || 0);
    els.storageClips.textContent = Number(stats.clips || 0).toLocaleString();
    els.storageImages.textContent = Number(stats.images || 0).toLocaleString();
    els.storageSize.textContent = formatBytes(stats.bytes || 0);
    if (!state.search && state.filter === 'all') {
      els.feedCount.textContent = plural(Number(stats.clips || 0), 'paste');
    }
  }

  async function refreshAll() {
    await Promise.allSettled([loadClips(true), refreshStats()]);
  }

  function scheduleStats() {
    window.clearTimeout(scheduleStats.timer);
    scheduleStats.timer = window.setTimeout(refreshStats, 350);
  }

  // ---------------------------------------------------------------------
  // Feed rendering
  // ---------------------------------------------------------------------

  function filteredClips() {
    const needle = state.search.trim().toLowerCase();
    return state.clips.filter((clip) => {
      if (state.filter === 'text' && clip.kind !== 'text') return false;
      if (state.filter === 'image' && clip.kind !== 'image') return false;
      if (state.filter === 'pinned' && !clip.pinned) return false;
      if (!needle) return true;
      if (clip.kind === 'text') return (clip.text || '').toLowerCase().includes(needle);
      return (clip.image && clip.image.name ? clip.image.name : '').toLowerCase().includes(needle);
    });
  }

  function renderFeed() {
    els.feed.setAttribute('aria-busy', state.loading ? 'true' : 'false');
    els.loadMoreWrap.hidden = !state.hasMore || state.loading;
    if (state.loading && !state.clips.length) {
      const grid = el('div', 'skeleton-grid');
      for (let i = 0; i < 6; i += 1) grid.append(el('div', 'skeleton'));
      els.feed.replaceChildren(grid);
      return;
    }

    if (state.error) {
      els.feed.replaceChildren(emptyState('cloud', 'Could not load your clipboard', state.error, {
        label: 'Try again',
        onClick: () => loadClips(true)
      }));
      return;
    }

    const list = filteredClips();
    if (!list.length) {
      if (state.search) {
        els.feed.replaceChildren(emptyState('search', `No matches for “${state.search}”`, 'Try a different word, or clear the search to see everything.', {
          label: 'Clear search',
          onClick: clearSearch
        }));
      } else if (state.filter === 'pinned') {
        els.feed.replaceChildren(emptyState('pin', 'Nothing pinned yet', 'Pin the things you reach for often and they will always be close at hand.'));
      } else if (state.filter === 'text') {
        els.feed.replaceChildren(emptyState('text', 'No text pastes yet', 'Paste a link or a note into the composer above and it will show up here.'));
      } else if (state.filter === 'image') {
        els.feed.replaceChildren(emptyState('image', 'No images yet', 'Drop a screenshot into the composer above to send it to every screen.'));
      } else {
        els.feed.replaceChildren(emptyState('clipboard', 'A blank page, for now', 'Paste text above, drop an image, or send something from another device. It will meet you here.', {
          label: 'Paste from clipboard',
          onClick: pasteFromClipboard
        }));
      }
      updateFeedCount(0);
      return;
    }

    const pinned = state.filter === 'pinned' ? [] : list.filter((clip) => clip.pinned);
    const regular = state.filter === 'pinned' ? list : list.filter((clip) => !clip.pinned);
    const fragment = document.createDocumentFragment();

    if (pinned.length) {
      const section = el('section', 'day-group');
      const label = el('h3', 'day-label');
      const pinnedLabel = el('span', 'pinned-label');
      pinnedLabel.append(icon('pin'), document.createTextNode('Pinned'));
      label.append(pinnedLabel);
      const grid = el('div', 'card-grid');
      for (const clip of pinned) grid.append(renderClip(clip));
      section.append(label, grid);
      fragment.append(section);
    }

    if (regular.length) {
      const groups = new Map();
      for (const clip of regular) {
        const key = dayKey(clip.createdAt);
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(clip);
      }
      for (const group of groups.values()) {
        const section = el('section', 'day-group');
        section.append(el('h3', 'day-label', dayLabel(group[0].createdAt)));
        const grid = el('div', 'card-grid');
        for (const clip of group) grid.append(renderClip(clip));
        section.append(grid);
        fragment.append(section);
      }
    }

    els.feed.replaceChildren(fragment);
    updateFeedCount(list.length);
  }

  function updateFeedCount(visible) {
    if (state.search || state.filter !== 'all') {
      els.feedCount.textContent = `${visible.toLocaleString()} shown`;
    } else if (state.stats) {
      els.feedCount.textContent = plural(Number(state.stats.clips || 0), 'paste');
    } else {
      els.feedCount.textContent = plural(visible, 'paste');
    }
  }

  function emptyState(iconName, title, copy, action) {
    const wrap = el('div', 'empty');
    const art = el('div', 'empty-illustration');
    art.append(icon(iconName));
    wrap.append(art, el('h3', null, title), el('p', null, copy));
    if (action) {
      const button = el('button', 'btn btn--quiet btn--small', action.label);
      button.type = 'button';
      button.style.marginTop = '18px';
      button.addEventListener('click', action.onClick);
      wrap.append(button);
    }
    return wrap;
  }

  function renderClip(clip) {
    const card = el('article', `clip${clip.pinned ? ' is-pinned' : ''}${state.newIds.has(clip.id) ? ' is-new' : ''}`);
    card.dataset.id = clip.id;

    const head = el('header', 'clip-head');
    const origin = el('div', 'clip-origin');
    const typeIcon = el('span', `type-icon${clip.kind === 'image' ? ' type-icon--image' : ''}`);
    typeIcon.append(icon(clip.kind === 'image' ? 'image' : 'text'));
    const device = el('span', 'clip-device', clip.device || 'Unknown device');
    const time = el('span', 'clip-time', relativeTime(clip.createdAt));
    time.title = absoluteTime(clip.createdAt);
    origin.append(typeIcon, device, time);

    const actions = el('div', 'clip-actions');
    const pinButton = el('button', `icon-btn${clip.pinned ? ' is-pinned' : ''}`);
    pinButton.type = 'button';
    pinButton.title = clip.pinned ? 'Unpin' : 'Pin to top';
    pinButton.setAttribute('aria-label', clip.pinned ? 'Unpin this paste' : 'Pin this paste');
    pinButton.append(icon('pin'));
    pinButton.addEventListener('click', () => togglePin(clip));
    const deleteButton = el('button', 'icon-btn is-danger');
    deleteButton.type = 'button';
    deleteButton.title = 'Delete';
    deleteButton.setAttribute('aria-label', 'Delete this paste');
    deleteButton.append(icon('trash'));
    deleteButton.addEventListener('click', () => requestDelete(clip));
    actions.append(pinButton, deleteButton);
    head.append(origin, actions);

    card.append(head);

    if (clip.kind === 'text') {
      const text = String(clip.text || '');
      const expanded = state.expanded.has(clip.id);
      const textBody = el('div', `clip-text${expanded ? ' is-expanded' : ''}`);
      appendLinkedText(textBody, text);
      const needsMore = text.length > 650 || text.split('\n').length > 13;
      if (needsMore && !expanded) {
        textBody.classList.add('is-clamped');
        const more = el('button', 'show-more', 'Show more');
        more.type = 'button';
        more.addEventListener('click', () => {
          state.expanded.add(clip.id);
          renderFeed();
        });
        card.append(textBody, more);
      } else if (expanded) {
        card.append(textBody);
        const less = el('button', 'show-more', 'Show less');
        less.type = 'button';
        less.addEventListener('click', () => {
          state.expanded.delete(clip.id);
          renderFeed();
        });
        card.append(less);
      } else {
        card.append(textBody);
      }
    } else {
      const wrap = el('button', 'clip-image-wrap');
      wrap.type = 'button';
      wrap.setAttribute('aria-label', 'Open image preview');
      const image = document.createElement('img');
      image.src = clip.image.url;
      image.alt = clip.image.name || 'Pasted image';
      image.loading = 'lazy';
      image.decoding = 'async';
      if (clip.image.width) image.width = clip.image.width;
      if (clip.image.height) image.height = clip.image.height;
      image.addEventListener('error', () => {
        wrap.classList.add('is-broken');
        wrap.replaceChildren(el('span', 'image-broken', 'Image preview unavailable'));
      });
      wrap.append(image);
      wrap.addEventListener('click', () => openLightbox(clip));
      card.append(wrap);
    }

    const foot = el('footer', 'clip-foot');
    const copyButton = el('button', 'copy-btn');
    copyButton.type = 'button';
    copyButton.append(icon('copy'), el('span', null, clip.kind === 'image' ? 'Copy image' : 'Copy text'));
    copyButton.addEventListener('click', () => copyClip(clip, copyButton));
    foot.append(copyButton);

    if (clip.kind === 'image') {
      const download = el('a', 'icon-btn');
      download.href = clip.image.downloadUrl;
      download.download = clip.image.name || 'pasta-image';
      download.title = 'Save image';
      download.setAttribute('aria-label', 'Save image');
      download.append(icon('download'));
      foot.append(download);
    }

    const meta = el('span', 'clip-meta');
    if (clip.kind === 'text') {
      meta.textContent = `${String(clip.text || '').length.toLocaleString()} chars`;
    } else {
      meta.textContent = formatBytes(clip.image ? clip.image.size : 0);
    }
    foot.append(meta);
    card.append(foot);
    return card;
  }

  function appendLinkedText(container, text) {
    const pattern = /(https?:\/\/[^\s<>"']+)/g;
    let lastIndex = 0;
    let match;
    while ((match = pattern.exec(text)) !== null) {
      if (match.index > lastIndex) container.append(document.createTextNode(text.slice(lastIndex, match.index)));
      let url = match[1];
      let trailing = '';
      while (/[.,;:!?)\]}]+$/.test(url) && url.length > 8) {
        trailing = url.slice(-1) + trailing;
        url = url.slice(0, -1);
      }
      const link = document.createElement('a');
      link.href = url;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      link.textContent = url;
      container.append(link);
      if (trailing) container.append(document.createTextNode(trailing));
      lastIndex = pattern.lastIndex;
    }
    if (lastIndex < text.length) container.append(document.createTextNode(text.slice(lastIndex)));
  }

  // ---------------------------------------------------------------------
  // Text composer
  // ---------------------------------------------------------------------

  function growComposer() {
    const input = els.composerInput;
    input.style.height = 'auto';
    input.style.height = `${Math.min(input.scrollHeight, 340)}px`;
  }

  function updateCharCount() {
    const length = els.composerInput.value.length;
    els.charCount.textContent = length ? `${length.toLocaleString()} / ${MAX_TEXT.toLocaleString()}` : '';
  }

  function setMode(mode) {
    state.mode = mode;
    for (const tab of els.tabs) {
      const active = tab.dataset.mode === mode;
      tab.classList.toggle('is-active', active);
      tab.setAttribute('aria-selected', active ? 'true' : 'false');
    }
    els.textPane.hidden = mode !== 'text';
    els.imagePane.hidden = mode !== 'image';
    if (mode === 'text') {
      growComposer();
      els.composerInput.focus({ preventScroll: true });
    }
  }

  function nudgeComposer() {
    els.composer.classList.remove('is-nudged');
    void els.composer.offsetWidth;
    els.composer.classList.add('is-nudged');
    window.setTimeout(() => els.composer.classList.remove('is-nudged'), 520);
    els.composerInput.focus();
  }

  function setSending(busy) {
    state.sending = busy;
    els.sendBtn.disabled = busy;
    const span = els.sendBtn.querySelector('span');
    if (span) span.textContent = busy ? 'Pasting…' : 'Paste it';
    els.pasteClipboardBtn.disabled = busy;
  }

  async function sendText(text, { clearComposer = false } = {}) {
    if (state.sending) return false;
    if (!text.trim()) {
      nudgeComposer();
      return false;
    }
    if (text.length > MAX_TEXT) {
      toast(`Text is limited to ${MAX_TEXT.toLocaleString()} characters.`, 'error');
      return false;
    }
    setSending(true);
    try {
      const data = await api('/api/clips', {
        method: 'POST',
        body: { text, device: state.device }
      });
      if (data && data.clip) {
        upsertClip(data.clip);
        markNew(data.clip.id);
        renderFeed();
        scheduleStats();
      }
      if (clearComposer) {
        els.composerInput.value = '';
        growComposer();
        updateCharCount();
      }
      toast('Text pasted. It is on every device.');
      return true;
    } catch (error) {
      if (error.status !== 401) toast(error.message, 'error');
      return false;
    } finally {
      setSending(false);
    }
  }

  async function sendComposer() {
    const text = els.composerInput.value;
    if (!text.trim()) {
      nudgeComposer();
      return;
    }
    await sendText(text, { clearComposer: true });
  }

  // ---------------------------------------------------------------------
  // Images
  // ---------------------------------------------------------------------

  async function decodeImage(file) {
    if ('createImageBitmap' in window) {
      try {
        return await createImageBitmap(file, { imageOrientation: 'from-image' });
      } catch {}
    }
    return new Promise((resolve, reject) => {
      const url = URL.createObjectURL(file);
      const image = new Image();
      image.onload = () => {
        URL.revokeObjectURL(url);
        resolve(image);
      };
      image.onerror = () => {
        URL.revokeObjectURL(url);
        reject(new Error('That image could not be read.'));
      };
      image.src = url;
    });
  }

  function canvasToBlob(canvas, type, quality) {
    return new Promise((resolve) => canvas.toBlob(resolve, type, quality));
  }

  async function prepareImage(file) {
    if (!file || !file.size) throw new Error('That image is empty.');
    if (file.size > 30 * 1024 * 1024) throw new Error('Images up to 30 MB can be prepared.');
    if (/hei[cf]/i.test(file.type) || /\.hei[cf]$/i.test(file.name || '')) {
      throw new Error('HEIC images are not supported by this browser. Export it as JPG or PNG first.');
    }
    const isGif = file.type === 'image/gif' || /\.gif$/i.test(file.name || '');
    if (isGif) {
      if (file.size > MAX_IMAGE) throw new Error('GIFs are limited to 20 MB.');
      const bitmap = await decodeImage(file);
      const width = bitmap.width || bitmap.naturalWidth;
      const height = bitmap.height || bitmap.naturalHeight;
      if (bitmap.close) bitmap.close();
      return { blob: file, width, height, name: file.name || 'pasted.gif' };
    }

    const bitmap = await decodeImage(file);
    const sourceWidth = bitmap.width || bitmap.naturalWidth;
    const sourceHeight = bitmap.height || bitmap.naturalHeight;
    if (!sourceWidth || !sourceHeight) throw new Error('That image could not be read.');
    const maxSide = 2400;
    const scale = Math.min(1, maxSide / Math.max(sourceWidth, sourceHeight));
    const width = Math.max(1, Math.round(sourceWidth * scale));
    const height = Math.max(1, Math.round(sourceHeight * scale));
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d', { alpha: true });
    if (!context) throw new Error('This browser could not prepare the image.');
    context.imageSmoothingEnabled = true;
    context.imageSmoothingQuality = 'high';
    context.drawImage(bitmap, 0, 0, width, height);
    if (bitmap.close) bitmap.close();

    let quality = 0.9;
    let blob = await canvasToBlob(canvas, 'image/webp', quality);
    if (!blob) blob = await canvasToBlob(canvas, 'image/png');
    while (blob && blob.size > 16 * 1024 * 1024 && quality > 0.55) {
      quality -= 0.1;
      blob = await canvasToBlob(canvas, 'image/webp', quality);
    }
    if (!blob) throw new Error('This browser could not prepare the image.');
    if (blob.size > MAX_IMAGE) throw new Error('That image is still over 20 MB after resizing. Try a smaller one.');
    const base = (file.name || 'pasted-image').replace(/\.[^.]+$/, '').slice(0, 90) || 'pasted-image';
    const extension = blob.type === 'image/png' ? 'png' : blob.type === 'image/jpeg' ? 'jpg' : 'webp';
    return { blob, width, height, name: `${base}.${extension}` };
  }

  function setImageBusy(busy) {
    els.imageDrop.classList.toggle('is-busy', busy);
    els.imageDrop.setAttribute('aria-busy', busy ? 'true' : 'false');
  }

  async function uploadImage(file) {
    if (!file) return;
    if (!(file.type || '').startsWith('image/') && !/\.(png|jpe?g|gif|webp)$/i.test(file.name || '')) {
      toast('That file is not an image.', 'error');
      return;
    }
    if (state.uploading) {
      toast('One image at a time — the first one is still going.');
      return;
    }
    state.uploading = true;
    setMode('image');
    setImageBusy(true);
    try {
      const prepared = await prepareImage(file);
      const response = await fetch('/api/clips/image', {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': prepared.blob.type,
          'X-Pasta-Filename': encodeURIComponent(prepared.name),
          'X-Pasta-Width': String(prepared.width),
          'X-Pasta-Height': String(prepared.height),
          'X-Pasta-Device': encodeURIComponent(state.device),
          'X-Pasta': '1'
        },
        body: prepared.blob
      });
      const data = await response.json().catch(() => null);
      if (!response.ok) {
        if (response.status === 401) handleSignedOut();
        const error = new Error(data && data.error ? data.error : 'The image could not be pasted.');
        error.status = response.status;
        throw error;
      }
      if (data && data.clip) {
        upsertClip(data.clip);
        markNew(data.clip.id);
        renderFeed();
        scheduleStats();
      }
      toast('Image pasted. It is on every device.');
    } catch (error) {
      if (error.status !== 401) toast(error.message || 'The image could not be pasted.', 'error');
    } finally {
      state.uploading = false;
      setImageBusy(false);
      els.fileInput.value = '';
    }
  }

  // ---------------------------------------------------------------------
  // Clipboard
  // ---------------------------------------------------------------------

  function legacyCopy(text) {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', 'readonly');
    area.style.position = 'fixed';
    area.style.top = '-1000px';
    area.style.opacity = '0';
    document.body.append(area);
    area.select();
    area.setSelectionRange(0, area.value.length);
    let okay = false;
    try {
      okay = document.execCommand('copy');
    } catch {}
    area.remove();
    return okay;
  }

  async function copyTextValue(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      try {
        await navigator.clipboard.writeText(text);
        return true;
      } catch {}
    }
    return legacyCopy(text);
  }

  async function copyClip(clip, button) {
    if (clip.kind === 'text') {
      const okay = await copyTextValue(String(clip.text || ''));
      if (okay) {
        flashButton(button);
        toast('Text copied to this device.');
      } else {
        toast('Your browser blocked clipboard access. Select the text and copy it manually.', 'error');
      }
      return;
    }

    try {
      await copyImageValue(clip);
      flashButton(button);
      toast('Image copied to this device.');
    } catch (error) {
      if (error.name === 'NotAllowedError') {
        toast('Clipboard access was blocked. Opening the image — you can copy it from there.', 'error');
      } else {
        toast('This browser could not place the image on the clipboard. Opening it instead.', 'error');
      }
      openLightbox(clip);
    }
  }

  async function imageToPngBlob(blob) {
    if (blob.type === 'image/png') return blob;
    const bitmap = await decodeImage(blob);
    const sourceWidth = bitmap.width || bitmap.naturalWidth;
    const sourceHeight = bitmap.height || bitmap.naturalHeight;
    const maxSide = 4096;
    const scale = Math.min(1, maxSide / Math.max(sourceWidth, sourceHeight));
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(sourceWidth * scale));
    canvas.height = Math.max(1, Math.round(sourceHeight * scale));
    const context = canvas.getContext('2d', { alpha: true });
    if (!context) throw new Error('Canvas unavailable.');
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    if (bitmap.close) bitmap.close();
    const png = await canvasToBlob(canvas, 'image/png');
    if (!png) throw new Error('Could not convert the image.');
    return png;
  }

  async function copyImageValue(clip) {
    if (!window.isSecureContext) throw new Error('Image copying needs HTTPS.');
    if (!window.ClipboardItem || !navigator.clipboard || !navigator.clipboard.write) {
      throw new Error('Image clipboard writing is not supported.');
    }
    const response = await fetch(clip.image.url, { credentials: 'same-origin', cache: 'force-cache' });
    if (!response.ok) throw new Error('Could not fetch the image.');
    const blob = await response.blob();
    const png = await imageToPngBlob(blob);
    const item = new ClipboardItem({ 'image/png': png });
    await navigator.clipboard.write([item]);
  }

  async function pasteFromClipboard() {
    if (!navigator.clipboard) {
      toast('This browser does not expose the clipboard. Use Ctrl+V in the composer instead.', 'error');
      return;
    }
    try {
      if (navigator.clipboard.read) {
        const items = await navigator.clipboard.read();
        for (const item of items) {
          for (const type of item.types) {
            if (type.startsWith('image/')) {
              const blob = await item.getType(type);
              const extension = type.split('/')[1] || 'png';
              await uploadImage(new File([blob], `clipboard.${extension}`, { type }));
              return;
            }
          }
        }
        for (const item of items) {
          if (item.types.includes('text/plain')) {
            const blob = await item.getType('text/plain');
            const text = await blob.text();
            if (text.trim()) {
              await sendText(text);
              return;
            }
          }
        }
        toast('Your clipboard is empty.');
        return;
      }
      const text = await navigator.clipboard.readText();
      if (text.trim()) {
        await sendText(text);
      } else {
        toast('Your clipboard is empty.');
      }
    } catch (error) {
      if (error && error.name === 'NotAllowedError') {
        toast('Clipboard access was blocked. Click in the composer and press Ctrl+V instead.', 'error');
      } else if (error && error.name === 'NotFoundError') {
        toast('Your clipboard is empty.');
      } else {
        toast('Could not read the clipboard. Use Ctrl+V in the composer instead.', 'error');
      }
    }
  }

  // ---------------------------------------------------------------------
  // Clip actions
  // ---------------------------------------------------------------------

  async function togglePin(clip) {
    try {
      const data = await api(`/api/clips/${encodeURIComponent(clip.id)}`, {
        method: 'PATCH',
        body: { pinned: !clip.pinned }
      });
      if (data && data.clip) {
        upsertClip(data.clip);
        renderFeed();
      }
      toast(clip.pinned ? 'Unpinned.' : 'Pinned to the top.');
    } catch (error) {
      if (error.status !== 401) toast(error.message, 'error');
    }
  }

  function requestDelete(clip) {
    askConfirm({
      eyebrow: 'REMOVE PASTE',
      title: 'Delete this paste?',
      copy: clip.kind === 'image'
        ? 'The image will be removed from every device. There is no undo.'
        : 'This text will be removed from every device. There is no undo.',
      okLabel: 'Delete',
      onConfirm: async () => {
        try {
          await api(`/api/clips/${encodeURIComponent(clip.id)}`, { method: 'DELETE' });
          state.clips = state.clips.filter((item) => item.id !== clip.id);
          state.expanded.delete(clip.id);
          renderFeed();
          scheduleStats();
          toast('Paste deleted.');
        } catch (error) {
          if (error.status !== 401) toast(error.message, 'error');
        }
      }
    });
  }

  function askConfirm({ eyebrow, title, copy, okLabel, onConfirm }) {
    els.confirmEyebrow.textContent = eyebrow || 'ARE YOU SURE?';
    els.confirmTitle.textContent = title || 'Are you sure?';
    els.confirmCopy.textContent = copy || '';
    els.confirmOk.textContent = okLabel || 'Confirm';
    if (els.confirmDialog.open) els.confirmDialog.close();
    state.confirmHandler = onConfirm;
    els.confirmDialog.showModal();
  }

  function openLightbox(clip) {
    if (!clip || !clip.image) return;
    state.lightboxClip = clip;
    els.lightboxImg.src = clip.image.url;
    els.lightboxImg.alt = clip.image.name || 'Pasted image';
    els.lightboxName.textContent = `${clip.image.name || 'image'} · ${formatBytes(clip.image.size)}`;
    els.lightboxDownload.href = clip.image.downloadUrl;
    els.lightboxDownload.setAttribute('download', clip.image.name || 'pasta-image');
    if (!els.lightbox.open) els.lightbox.showModal();
  }

  // ---------------------------------------------------------------------
  // Search / filters
  // ---------------------------------------------------------------------

  function clearSearch() {
    els.searchInput.value = '';
    els.clearSearch.hidden = true;
    state.search = '';
    loadClips(true);
  }

  let searchTimer = 0;
  function onSearchInput() {
    const value = els.searchInput.value;
    els.clearSearch.hidden = !value;
    window.clearTimeout(searchTimer);
    searchTimer = window.setTimeout(() => {
      state.search = value.trim();
      loadClips(true);
    }, 260);
  }

  // ---------------------------------------------------------------------
  // Settings & sessions
  // ---------------------------------------------------------------------

  async function openSettings() {
    els.deviceNameInput.value = state.device;
    if (!els.settingsDialog.open) els.settingsDialog.showModal();
    refreshStats();
    await loadSessions();
  }

  async function loadSessions() {
    els.sessionList.replaceChildren(el('p', 'muted', 'Loading sessions…'));
    try {
      const data = await api('/api/sessions');
      renderSessions(data.sessions || []);
    } catch (error) {
      if (error.status !== 401) els.sessionList.replaceChildren(el('p', 'muted', error.message || 'Could not load sessions.'));
    }
  }

  function renderSessions(sessions) {
    if (!sessions.length) {
      els.sessionList.replaceChildren(el('p', 'muted', 'No active sessions.'));
      return;
    }
    const fragment = document.createDocumentFragment();
    for (const session of sessions) {
      const row = el('div', 'session-item');
      const iconWrap = el('span', 'session-icon');
      iconWrap.append(icon(session.kind === 'android' ? 'android' : 'clipboard'));
      const info = el('div', 'session-info');
      const nameLine = el('strong', null, session.label || 'Browser');
      info.append(nameLine);
      const detail = session.current
        ? 'This device'
        : `Last used ${relativeTime(session.lastUsed)} · expires ${new Date(session.expiresAt).toLocaleDateString()}`;
      info.append(el('span', null, detail));
      row.append(iconWrap, info);
      if (session.current) {
        row.append(el('span', 'session-current', 'Current'));
      } else {
        const revoke = el('button', 'session-revoke', 'Revoke');
        revoke.type = 'button';
        revoke.addEventListener('click', () => {
          askConfirm({
            eyebrow: 'ACTIVE SESSION',
            title: 'Sign out this device?',
            copy: `“${session.label}” will need the passphrase to open Pasta again.`,
            okLabel: 'Sign out',
            onConfirm: async () => {
              try {
                await api(`/api/sessions/${encodeURIComponent(session.id)}`, { method: 'DELETE' });
                await loadSessions();
                toast('Session revoked.');
              } catch (error) {
                if (error.status !== 401) toast(error.message, 'error');
              }
            }
          });
        });
        row.append(revoke);
      }
      fragment.append(row);
    }
    els.sessionList.replaceChildren(fragment);
  }

  function saveDeviceName() {
    const value = els.deviceNameInput.value.trim().slice(0, 48);
    if (!value) {
      els.deviceNameInput.focus();
      return;
    }
    state.device = value;
    try { localStorage.setItem('pasta.device', value); } catch {}
    updateDeviceLabel();
    toast(`This browser is now “${value}”.`);
  }

  async function copyAppLink() {
    const url = `${location.origin}/downloads/Pasta.apk`;
    const okay = await copyTextValue(url);
    toast(okay ? 'Android app link copied.' : 'Could not copy the link.', okay ? 'ok' : 'error');
  }

  // ---------------------------------------------------------------------
  // Live events
  // ---------------------------------------------------------------------

  let events = null;

  function connectEvents() {
    if (!('EventSource' in window) || events) return;
    setLive(false);
    try {
      events = new EventSource('/api/events');
    } catch {
      setLive(false);
      return;
    }
    events.addEventListener('open', () => {
      const reconnect = state.hasConnected;
      state.hasConnected = true;
      setLive(true);
      if (reconnect) loadClips(true);
    });
    events.addEventListener('error', () => setLive(false));
    events.addEventListener('clip-created', (event) => {
      const payload = parseEvent(event);
      if (!payload || !payload.clip) return;
      upsertClip(payload.clip);
      markNew(payload.clip.id);
      renderFeed();
      scheduleStats();
      if (payload.clip.device !== state.device) {
        toast(`New from ${payload.clip.device}.`);
        markUnread();
      }
    });
    events.addEventListener('clip-updated', (event) => {
      const payload = parseEvent(event);
      if (payload && payload.clip) {
        upsertClip(payload.clip);
        renderFeed();
      }
    });
    events.addEventListener('clip-deleted', (event) => {
      const payload = parseEvent(event);
      if (payload && payload.id) {
        state.clips = state.clips.filter((clip) => clip.id !== payload.id);
        renderFeed();
        scheduleStats();
      }
    });
  }

  function parseEvent(event) {
    try {
      return JSON.parse(event.data);
    } catch {
      return null;
    }
  }

  function closeEvents() {
    if (events) {
      events.close();
      events = null;
    }
    state.hasConnected = false;
    setLive(false);
  }

  function markUnread() {
    if (!document.hidden) return;
    state.unread += 1;
    document.title = `(${state.unread}) Pasta`;
  }

  function clearUnread() {
    if (!state.unread) return;
    state.unread = 0;
    document.title = state.me ? 'Pasta' : 'Pasta — one clipboard, everywhere';
  }

  // ---------------------------------------------------------------------
  // Drag & drop + paste
  // ---------------------------------------------------------------------

  let dragDepth = 0;

  function hasFiles(event) {
    const types = event.dataTransfer && event.dataTransfer.types;
    return types && Array.from(types).includes('Files');
  }

  function hideDrop() {
    dragDepth = 0;
    els.dropOverlay.hidden = true;
  }

  function bindDrop() {
    document.addEventListener('dragenter', (event) => {
      if (!hasFiles(event) || !state.me) return;
      event.preventDefault();
      dragDepth += 1;
      els.dropOverlay.hidden = false;
    });
    document.addEventListener('dragover', (event) => {
      if (!hasFiles(event) || !state.me) return;
      event.preventDefault();
      event.dataTransfer.dropEffect = 'copy';
    });
    document.addEventListener('dragleave', (event) => {
      if (!hasFiles(event) || !state.me) return;
      dragDepth = Math.max(0, dragDepth - 1);
      if (!dragDepth) els.dropOverlay.hidden = true;
    });
    document.addEventListener('drop', (event) => {
      if (!hasFiles(event) || !state.me) return;
      event.preventDefault();
      hideDrop();
      const files = Array.from(event.dataTransfer.files || []);
      const image = files.find((file) => (file.type || '').startsWith('image/'));
      if (image) uploadImage(image);
      else if (files.length) toast('Drop an image file — PNG, JPG, GIF or WebP.', 'error');
    });
    window.addEventListener('blur', hideDrop);
  }

  function bindGlobalPaste() {
    document.addEventListener('paste', (event) => {
      if (!state.me || els.appView.hidden) return;
      const data = event.clipboardData;
      if (!data) return;
      const imageItem = Array.from(data.items || []).find((item) => item.kind === 'file' && item.type.startsWith('image/'));
      if (imageItem) {
        event.preventDefault();
        uploadImage(imageItem.getAsFile());
        return;
      }
      const target = event.target;
      const inField = target && target.closest && target.closest('input, textarea, [contenteditable="true"]');
      if (inField) return;
      const text = data.getData('text/plain');
      if (!text.trim()) return;
      event.preventDefault();
      setMode('text');
      els.composerInput.value = text;
      growComposer();
      updateCharCount();
      els.composerInput.focus();
      toast('Pasted into the composer. Edit it or press Paste it.');
    });
  }

  // ---------------------------------------------------------------------
  // Dialogs and bindings
  // ---------------------------------------------------------------------

  function bindDialogs() {
    for (const button of $$('[data-close-dialog]')) {
      button.addEventListener('click', () => {
        const dialog = button.closest('dialog');
        if (dialog) dialog.close();
      });
    }
    for (const dialog of $$('dialog')) {
      dialog.addEventListener('click', (event) => {
        if (event.target !== dialog) return;
        const rect = dialog.getBoundingClientRect();
        const inside = event.clientX >= rect.left && event.clientX <= rect.right && event.clientY >= rect.top && event.clientY <= rect.bottom;
        if (!inside) dialog.close();
      });
    }
    els.lightbox.addEventListener('close', () => {
      els.lightboxImg.removeAttribute('src');
      state.lightboxClip = null;
    });
    els.confirmDialog.addEventListener('close', () => {
      state.confirmHandler = null;
    });
    els.confirmCancel.addEventListener('click', () => els.confirmDialog.close());
    els.confirmOk.addEventListener('click', async () => {
      const handler = state.confirmHandler;
      els.confirmDialog.close();
      if (handler) await handler();
    });
    els.confirmDialog.addEventListener('cancel', () => {
      state.confirmHandler = null;
    });
  }

  function bindEvents() {
    els.loginForm.addEventListener('submit', submitLogin);
    els.togglePassword.addEventListener('click', togglePasswordVisibility);

    for (const tab of els.tabs) {
      tab.addEventListener('click', () => setMode(tab.dataset.mode));
    }
    els.composerInput.addEventListener('input', () => {
      growComposer();
      updateCharCount();
    });
    els.composerInput.addEventListener('keydown', (event) => {
      if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
        event.preventDefault();
        sendComposer();
      }
    });
    els.sendBtn.addEventListener('click', sendComposer);
    els.attachBtn.addEventListener('click', () => {
      setMode('image');
      els.fileInput.click();
    });

    els.imageDrop.addEventListener('click', () => els.fileInput.click());
    els.imageDrop.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        els.fileInput.click();
      }
    });
    els.imageDrop.addEventListener('dragover', (event) => {
      event.preventDefault();
      els.imageDrop.classList.add('is-over');
    });
    els.imageDrop.addEventListener('dragleave', () => els.imageDrop.classList.remove('is-over'));
    els.imageDrop.addEventListener('drop', (event) => {
      event.preventDefault();
      els.imageDrop.classList.remove('is-over');
      const file = event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files[0];
      if (file) uploadImage(file);
    });
    els.fileInput.addEventListener('change', () => {
      const file = els.fileInput.files && els.fileInput.files[0];
      if (file) uploadImage(file);
    });

    els.pasteClipboardBtn.addEventListener('click', pasteFromClipboard);
    els.settingsBtn.addEventListener('click', openSettings);
    els.footerSettings.addEventListener('click', openSettings);
    els.refreshBtn.addEventListener('click', async () => {
      els.refreshBtn.classList.add('is-spinning');
      await Promise.allSettled([loadClips(true), refreshStats()]);
      window.setTimeout(() => els.refreshBtn.classList.remove('is-spinning'), 550);
    });

    els.searchInput.addEventListener('input', onSearchInput);
    els.searchInput.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        clearSearch();
      }
    });
    els.clearSearch.addEventListener('click', clearSearch);

    for (const filter of els.filters) {
      filter.addEventListener('click', () => {
        state.filter = filter.dataset.filter || 'all';
        for (const item of els.filters) item.classList.toggle('is-active', item === filter);
        renderFeed();
      });
    }

    els.loadMore.addEventListener('click', () => loadClips(false));

    els.saveDeviceBtn.addEventListener('click', saveDeviceName);
    els.deviceNameInput.addEventListener('keydown', (event) => {
      if (event.key === 'Enter') {
        event.preventDefault();
        saveDeviceName();
      }
    });
    els.signoutBtn.addEventListener('click', () => {
      askConfirm({
        eyebrow: 'SIGN OUT',
        title: 'Sign out of this device?',
        copy: 'You will need the passphrase to unlock this clipboard again. Other devices stay signed in.',
        okLabel: 'Sign out',
        onConfirm: doLogout
      });
    });
    els.copyAppLink.addEventListener('click', copyAppLink);
    els.lightboxCopy.addEventListener('click', () => {
      if (state.lightboxClip) copyClip(state.lightboxClip, els.lightboxCopy);
    });

    document.addEventListener('keydown', (event) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        if (state.me) {
          event.preventDefault();
          els.searchInput.focus();
          els.searchInput.select();
        }
      }
    });

    document.addEventListener('visibilitychange', () => {
      if (!document.hidden) {
        clearUnread();
        if (state.me && (!events || events.readyState === EventSource.CLOSED)) {
          closeEvents();
          connectEvents();
          loadClips(true);
        }
      }
    });
    window.addEventListener('focus', clearUnread);

    bindDrop();
    bindGlobalPaste();
    bindDialogs();
  }

  // ---------------------------------------------------------------------
  // PWA
  // ---------------------------------------------------------------------

  function registerServiceWorker() {
    if (!('serviceWorker' in navigator)) return;
    window.addEventListener('load', () => {
      navigator.serviceWorker.register('/sw.js').catch(() => {});
    });
  }

  // ---------------------------------------------------------------------
  // Boot
  // ---------------------------------------------------------------------

  async function init() {
    state.device = state.device || loadDevice();
    els.loginHost.textContent = location.host || 'pasta';
    updateDeviceLabel();
    growComposer();
    updateCharCount();
    bindEvents();
    registerServiceWorker();
    await checkAuth();
  }

  init().catch(() => {
    showLogin();
  });
})();
