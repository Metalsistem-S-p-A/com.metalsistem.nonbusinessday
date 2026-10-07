(function ($) {
  try {
    if (!window.__msNbdFC) {
      $('<style>').text(__MS_SKELETON_CSS__).appendTo('head');
  	  $('<style>').text(__MS_THEME_CSS__).appendTo('head');
  	  $('<style>').text(__MS_PURPLE_CSS__).appendTo('head');
  	  $('<style>').text(__MS_CUSTOM_CSS__).appendTo('head');
      $.globalEval(__MS_FC_MAIN__);
      $.globalEval(__MS_FC_THEME__);
      $.globalEval(__MS_FC_LOCALES__);
      window.__msNbdFC = true;
    }

    window.msNbd = window.msNbd || {};

    // Build the FullCalendar month view. A selection relays "new|start|end" and
    // an event click relays "edit|id" to the server. Week starts Monday.
    window.msNbd.create = function (uuid, locale) {
      try {
        var el = document.getElementById(uuid);
        if (!el || typeof FullCalendar === 'undefined') {
          if (el) el.innerHTML = '<div style="color:#b00;padding:12px">FullCalendar not loaded.</div>';
          return;
        }
        if (window.msNbd['i_' + uuid]) return;
        var cal = new FullCalendar.Calendar(el, {
          initialView: 'multiMonthYear',
          headerToolbar: { start: 'title', center: '', end: 'dayGridMonth,multiMonthYear today prev,next' },
          firstDay: 1,
          locale: locale || 'it',
          height: '100%',
          selectable: true,
	      eventClass: "pointer-cursor",
          events: function (info, successCallback) {
            window.msNbd['cb_' + uuid] = successCallback;
            zAu.send(new zk.Event(zk.Widget.$(uuid), 'onCalDates', info.startStr + '|' + info.endStr, { toServer: true }));
          },
          loading: function (isLoading) {
            if (isLoading) zAu.cmd0.showBusy(null);
            else zAu.cmd0.clearBusy();
          },
          select: function (info) { zAu.send(new zk.Event(zk.Widget.$(uuid), 'onCalSel', 'new|' + info.startStr + '|' + info.endStr, { toServer: true })); cal.unselect(); },
          eventClick: function (info) { zAu.send(new zk.Event(zk.Widget.$(uuid), 'onCalSel', 'edit|' + info.event.id, { toServer: true })); }
        });
        window.msNbd['i_' + uuid] = cal;
        cal.render();
      } catch (err) {
        var e2 = document.getElementById(uuid);
        if (e2) e2.innerHTML = '<div style="color:#b00;padding:12px">Error calendar: ' + (err && err.message) + '</div>';
        if (window.console) console.error('msNbd.create', err);
      }
    };

    // Server pushes the events for the requested range through the pending
    // FullCalendar fetch callback; loading(false) then fires after render.
    window.msNbd.deliver = function (uuid, events) {
      var cb = window.msNbd['cb_' + uuid];
      window.msNbd['cb_' + uuid] = null;
      if (cb) { cb(events || []); return; }
      var cal = window.msNbd['i_' + uuid];
      if (cal) { cal.removeAllEvents(); (events || []).forEach(function (ev) { cal.addEvent(ev); }); }
    };

    window.msNbd.refetch = function (uuid) {
      var cal = window.msNbd['i_' + uuid];
      if (cal) cal.refetchEvents();
    };

    // Incremental update: add or replace single events without a full refetch.
    window.msNbd.upsertAll = function (uuid, evs) {
      var cal = window.msNbd['i_' + uuid];
      if (!cal || !evs) return;
      evs.forEach(function (ev) {
        var ex = cal.getEventById(ev.id);
        if (ex) ex.remove();
        cal.addEvent(ev);
      });
    };

    window.msNbd.removeEvent = function (uuid, id) {
      var cal = window.msNbd['i_' + uuid];
      if (!cal) return;
      var ex = cal.getEventById(String(id));
      if (ex) ex.remove();
    };

    window.msNbd.create(__MS_UUID__, __MS_LOCALE__);
  } catch (e) {
    var el = document.getElementById(__MS_UUID__);
    if (el) el.innerHTML = '<div style="color:#b00;padding:12px">Error calendar: ' + (e && e.message) + '</div>';
    if (window.console) console.error('msNbd', e);
  }
})(jQuery);
