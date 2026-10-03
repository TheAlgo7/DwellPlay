// Tests the actual HTML asset at its official IFrame API boundary, with no network or packages.
// Run from the repository root: node tools/test-youtube-player.cjs
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/youtube_player.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
let count = 0;

function setup(apiAvailable = true) {
  const players = [], calls = [], progress = [], states = [];
  let tick;
  const ctx = {
    document: {getElementById: () => ({}), createElement: () => ({}), body: {appendChild() {}}},
    setInterval(fn) {tick = fn; return 1;}, clearInterval() {tick = null;},
    DwellBridge: {onApiReady() {}, onReady() {}, onError() {}, onState(s) {states.push(s);}, onProgress(...args) {progress.push(args);}},
  };
  ctx.window = ctx;
  const YT = {Player: function(id, options) {
    this.options = options;
    this.state = -1;
    this.time = 0;
    this.index = 0;
    players.push(this);
    for (const name of ['playVideo', 'pauseVideo', 'stopVideo', 'destroy', 'loadPlaylist', 'cuePlaylist', 'loadVideoById', 'cueVideoById']) {
      this[name] = arg => calls.push([name, arg]);
    }
    this.getPlayerState = () => this.state;
    this.getCurrentTime = () => this.time;
    this.getDuration = () => 120;
    this.getPlaylistIndex = () => this.index;
    this.getVideoData = () => ({video_id: 'current'});
  }};
  if (apiAvailable) ctx.YT = YT;
  vm.createContext(ctx);
  vm.runInContext(script, ctx);
  return {
    ctx, players, calls, progress, states,
    api() {ctx.YT = YT; ctx.onYouTubeIframeAPIReady();},
    ready(i = players.length - 1) {const p = players[i]; p.options.events.onReady({target: p});},
    tick() {tick?.();},
  };
}

function check(name, fn) {
  try {fn(); count++;} catch (error) {error.message = `${name}: ${error.message}`; throw error;}
}

check('play before API ready retains single-video time', () => {
  const h = setup(false);
  h.ctx.dwell.load('one', null, 27, false, false, 0);
  h.ctx.dwell.play(); h.api(); h.ready();
  assert.equal(h.players[0].options.playerVars.start, 27);
  assert.equal(h.calls.at(-1)[0], 'playVideo');
});
check('play during a paused iframe remount is retained', () => {
  const h = setup();
  h.ctx.dwell.load('one', null, 64, false, false, 0);
  h.ctx.dwell.play(); h.ready();
  assert.equal(h.calls.at(-1)[0], 'playVideo');
  assert.equal(h.players[0].options.playerVars.start, 64);
});
check('pause before ready overrides autoplay', () => {
  const h = setup();
  h.ctx.dwell.load('one', null, 64, true, false, 0);
  h.ctx.dwell.pause(); h.ready();
  assert.equal(h.calls.at(-1)[0], 'pauseVideo');
});
check('paused single-video remount stays paused at its snapshot', () => {
  const h = setup();
  h.ctx.dwell.load('one', null, 64, false, true, 0); h.ready();
  assert.equal(h.players[0].options.playerVars.autoplay, 0);
  assert.equal(h.players[0].options.playerVars.start, 64);
  assert.equal(h.players[0].options.playerVars.cc_load_policy, 1);
  assert.equal(h.calls.at(-1)[0], 'pauseVideo');
});
check('play before ready restores playlist time and index', () => {
  const h = setup();
  h.ctx.dwell.load(null, 'playlist', 47, false, false, 3);
  h.ctx.dwell.play(); h.ready();
  const [action, args] = h.calls[0];
  assert.equal(action, 'loadPlaylist');
  assert.equal(args.startSeconds, 47); assert.equal(args.index, 3);
});
check('pause before API ready cues the original playlist item', () => {
  const h = setup(false);
  h.ctx.dwell.load(null, 'playlist', 21, true, false, 4);
  h.ctx.dwell.pause(); h.api(); h.ready();
  const [action, args] = h.calls[0];
  assert.equal(action, 'cuePlaylist');
  assert.equal(args.startSeconds, 21); assert.equal(args.index, 4);
});
check('latest transport intent wins while loading', () => {
  const h = setup();
  h.ctx.dwell.load('one', null, 27, false, false, 0);
  h.ctx.dwell.play(); h.ctx.dwell.pause(); h.ready();
  assert.equal(h.calls.at(-1)[0], 'pauseVideo');
});
check('stop cancels a queued pre-API play', () => {
  const h = setup(false);
  h.ctx.dwell.load('one', null, 27, false, false, 0);
  h.ctx.dwell.play(); h.ctx.dwell.stop(); h.api();
  assert.equal(h.players.length, 0);
});
check('stop rejects an old iframe ready callback', () => {
  const h = setup();
  h.ctx.dwell.load('one', null, 27, false, false, 0);
  h.ctx.dwell.play(); h.ctx.dwell.stop(); h.ready(0);
  assert.equal(h.calls.filter(c => c[0] === 'playVideo').length, 0);
});
check('loading replacement rejects old iframe state callbacks', () => {
  const h = setup();
  h.ctx.dwell.load('one', null, 0, false, false, 0);
  const old = h.players[0];
  h.ctx.dwell.load('two', null, 10, false, false, 0);
  old.options.events.onStateChange({target: old, data: 2}); h.ready(0);
  assert.equal(h.states.length, 0);
  assert.equal(h.calls.filter(c => c[0] === 'pauseVideo').length, 0);
});
check('cued zero progress does not overwrite a remount snapshot', () => {
  const h = setup();
  h.ctx.dwell.load(null, 'playlist', 47, false, false, 3); h.ready();
  h.players[0].state = 5; h.tick();
  assert.equal(h.progress.length, 0);
  h.players[0].state = 2; h.players[0].time = 47; h.players[0].index = 3; h.tick();
  assert.equal(h.progress[0][0], 47); assert.equal(h.progress[0][3], 3);
});
check('a ready player receives immediate transport controls', () => {
  const h = setup();
  h.ctx.dwell.load('one', null, 0, false, false, 0); h.ready();
  h.ctx.dwell.play(); h.ctx.dwell.pause();
  assert.deepEqual(h.calls.slice(-2).map(c => c[0]), ['playVideo', 'pauseVideo']);
});

console.log(`YouTube HTML asset: ${count} boundary regression checks passed.`);
