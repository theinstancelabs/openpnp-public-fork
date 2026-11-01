/* app.js: boots the page. Loaded last (defer), after core.js and every workspace module registered itself with
   IL.workspace(). Kept as a separate file because the API server test fetches /static/app.js. */
'use strict';
IL.boot();
