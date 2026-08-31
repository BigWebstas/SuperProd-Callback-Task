/*
 * Missed Call Bridge — spike plugin
 * ---------------------------------
 * Throwaway instrument for the Part 11 spike. It does no real work. It only
 * proves, from inside Super Productivity on Android:
 *
 *   Task 2  the plugin loads and PluginAPI is reachable
 *   Task 2  getAllProjects / getAllTags return real data
 *   Task 2  persistDataSynced / setSecret round-trip
 *   Task 3  channel B2: PluginAPI.request can reach the app's 127.0.0.1 server
 *   Task 4  channel B1: the taskCreated hook fires for deep-link tasks and
 *           carries enough to enrich the task
 *
 * Watch the SP dev console / logcat (filter: MCB) while it runs.
 * Everything is inlined — plugins cannot load external scripts.
 */

(function () {
  'use strict';

  var TAG = '[MCB]';
  var BRIDGE_PORT = 47623;              // must match the Android app's loopback server
  var BRIDGE_HOST = 'localhost';        // Task 3 flips this to 'localhost' as a variant
  var POLL_MS = 5000;
  var MARKER_PREFIX = 'sp-cb:';         // Task 4: app packs JSON after this in task notes

  var log = function () {
    var args = Array.prototype.slice.call(arguments);
    args.unshift(TAG);
    console.log.apply(console, args);
  };
  var warn = function () {
    var args = Array.prototype.slice.call(arguments);
    args.unshift(TAG);
    console.warn.apply(console, args);
  };

  var pollTimer = null;

  // ---------------------------------------------------------------------------
  // Task 2 — environment and API surface
  // ---------------------------------------------------------------------------
  function reportEnvironment() {
    try {
      var cfg = PluginAPI.cfg || PluginAPI.getConfig || null;
      log('PluginAPI keys:', Object.keys(PluginAPI).join(', '));
      log('platform:', PluginAPI.platform || (cfg && cfg.platform) || 'unknown');
    } catch (e) {
      warn('environment probe failed', e);
    }
  }

  async function probeReads() {
    try {
      var projects = await PluginAPI.getAllProjects();
      log('getAllProjects ->', projects.length, 'projects');
      projects.slice(0, 5).forEach(function (p) {
        log('  project', JSON.stringify({ id: p.id, title: p.title }));
      });
    } catch (e) {
      warn('getAllProjects failed', e);
    }

    try {
      var tags = await PluginAPI.getAllTags();
      log('getAllTags ->', tags.length, 'tags');
    } catch (e) {
      warn('getAllTags failed', e);
    }
  }

  async function probePersistence() {
    try {
      var payload = JSON.stringify({ ts: Date.now(), note: 'spike round-trip' });
      await PluginAPI.persistDataSynced(payload);
      var back = await PluginAPI.loadSyncedData();
      log('persistDataSynced round-trip ok:', back === payload, back);
    } catch (e) {
      warn('persistDataSynced round-trip failed', e);
    }

    try {
      await PluginAPI.setSecret('spike-secret', 'hunter2');
      var s = await PluginAPI.getSecret('spike-secret');
      log('setSecret round-trip ok:', s === 'hunter2');
      await PluginAPI.deleteSecret('spike-secret');
    } catch (e) {
      warn('secret round-trip failed (may be desktop-only)', e);
    }
  }

  // ---------------------------------------------------------------------------
  // Task 2 — config dialog with a real <select> of projects
  // Trigger it from the registered header button.
  // ---------------------------------------------------------------------------
  async function openConfigDialog() {
    var projects = [];
    try {
      projects = await PluginAPI.getAllProjects();
    } catch (e) {
      warn('cannot load projects for dialog', e);
    }

    var options = projects
      .map(function (p) {
        return '<option value="' + p.id + '">' + escapeHtml(p.title) + '</option>';
      })
      .join('');

    var html =
      '<label style="display:block;margin-bottom:8px">Target project' +
      '<select id="mcb-project" style="display:block;width:100%;margin-top:4px">' +
      options +
      '</select></label>' +
      '<label style="display:block">Bridge host' +
      '<input id="mcb-host" type="text" value="' + BRIDGE_HOST + '" style="display:block;width:100%;margin-top:4px"></label>';

    try {
      var result = await PluginAPI.openDialog({
        title: 'Missed Call Bridge (spike)',
        htmlContent: html,
        buttons: [
          { label: 'Cancel', role: 'cancel' },
          { label: 'Save', role: 'submit' }
        ]
      });
      log('openDialog returned:', JSON.stringify(result));
    } catch (e) {
      warn('openDialog failed', e);
    }
  }

  // ---------------------------------------------------------------------------
  // Task 3 — channel B2: poll the Android app's loopback HTTP server
  // ---------------------------------------------------------------------------
  function bridgeUrl(path) {
    return 'http://' + BRIDGE_HOST + ':' + BRIDGE_PORT + path;
  }

  function readBody(res) {
    var raw = res && (res.data != null ? res.data : res.body != null ? res.body : res.text);
    if (typeof raw === 'string') {
      try { return JSON.parse(raw); } catch (e) { return raw; }
    }
    return raw;
  }

  async function pollBridge() {
    try {
      var res = await PluginAPI.request({ url: bridgeUrl('/pending'), method: 'GET' });
      log('B2 request ok:', JSON.stringify({ status: res && res.status }));
      var body = readBody(res);
      var calls = (body && body.calls) || [];
      log('B2 pending:', calls.length);

      for (var i = 0; i < calls.length; i++) {
        var call = calls[i];
        log('  would addTask for', JSON.stringify({ id: call.id, number: call.number, name: call.name }));
        // Real plugin: await PluginAPI.addTask({ title, notes, projectId, tagIds, dueDay });
        await ackBridge(call.id);
      }
    } catch (e) {
      warn('B2 request FAILED — capture this error verbatim for the spike:', e && (e.message || e), e);
    }
  }

  async function ackBridge(id) {
    try {
      var res = await PluginAPI.request({
        url: bridgeUrl('/ack'),
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id: id })
      });
      log('  ack', id, '->', JSON.stringify(readBody(res)));
    } catch (e) {
      warn('  ack failed for', id, e && (e.message || e));
    }
  }

  // ---------------------------------------------------------------------------
  // Task 4 — channel B1: enrich tasks created via the create-task deep link
  // ---------------------------------------------------------------------------
  function registerTaskCreatedHook() {
    var HOOK =
      (PluginAPI.Hooks && (PluginAPI.Hooks.TASK_CREATED || PluginAPI.Hooks.TASK_CREATE)) ||
      'taskCreated';

    try {
      PluginAPI.registerHook(HOOK, async function (payload) {
        log('taskCreated hook fired. payload:', JSON.stringify(payload));

        var task = normalizeHookTask(payload);
        if (!task) {
          warn('hook payload has no usable task/id — B1 needs a fallback poll of getTasks()');
          return;
        }

        var marker = extractMarker(task.notes || '');
        if (!marker) {
          log('no marker in notes — not one of ours, ignoring');
          return;
        }
        log('parsed marker:', JSON.stringify(marker));

        try {
          await PluginAPI.updateTask(task.id, {
            notes: stripMarker(task.notes || ''),
            // dueDay: marker.due,        // fill from marker in the real plugin
            // tagIds: [...],
            // projectId: '...'
          });
          log('updateTask ok for', task.id, '(marker stripped)');
        } catch (e) {
          warn('updateTask failed', e);
        }
      });
      log('registered hook:', HOOK);
    } catch (e) {
      warn('registerHook failed', e);
    }
  }

  function normalizeHookTask(payload) {
    if (!payload) return null;
    if (typeof payload === 'string') return { id: payload, notes: '' };
    if (payload.task && payload.task.id) return payload.task;
    if (payload.id) return payload;
    if (payload.taskId) return { id: payload.taskId, notes: payload.notes || '' };
    return null;
  }

  function extractMarker(notes) {
    var i = notes.indexOf(MARKER_PREFIX);
    if (i === -1) return null;
    var rest = notes.slice(i + MARKER_PREFIX.length).trim();
    try {
      return JSON.parse(rest);
    } catch (e) {
      // marker might be followed by other text; try the first {...} block
      var m = rest.match(/\{[^}]*\}/);
      if (m) {
        try { return JSON.parse(m[0]); } catch (e2) { /* fall through */ }
      }
      return { raw: rest };
    }
  }

  function stripMarker(notes) {
    var i = notes.indexOf(MARKER_PREFIX);
    return i === -1 ? notes : notes.slice(0, i).trim();
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  // ---------------------------------------------------------------------------
  // Startup
  // ---------------------------------------------------------------------------
  async function start() {
    log('plugin.js running');

    try {
      PluginAPI.showSnack({ msg: 'Missed Call Bridge spike loaded', type: 'SUCCESS' });
    } catch (e) {
      warn('showSnack failed', e);
    }

    try {
      PluginAPI.registerHeaderButton({
        label: 'MCB spike',
        icon: 'bug_report',
        onClick: openConfigDialog
      });
    } catch (e) {
      warn('registerHeaderButton failed', e);
    }

    reportEnvironment();
    await probeReads();
    await probePersistence();
    registerTaskCreatedHook();

    pollTimer = setInterval(pollBridge, POLL_MS);
    pollBridge();
  }

  function stop() {
    if (pollTimer) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
    log('plugin unloaded');
  }

  // plugin.onReady / onUnload exist in newer SP; guard for older builds.
  if (typeof plugin !== 'undefined' && plugin.onReady) {
    plugin.onReady(start);
    if (plugin.onUnload) plugin.onUnload(stop);
  } else {
    start();
  }
})();
