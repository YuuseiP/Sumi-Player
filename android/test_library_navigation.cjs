const assert = require('node:assert/strict');
const { values, destinations } = require('./ui/library-navigation.js');
const album = 'A / B "live"';
const solo = { album, artist: 'A feat. B', credits: [{ id: 'artist-1', name: 'A', kind: 'artist' }] };
const cast = { album, artist: '角色甲 / 组合乙', credits: [
  { id: 'character-1', name: '角色甲', kind: 'character' },
  { id: 'group-1', name: '组合乙', kind: 'group' },
  { id: 'artist-2', name: '歌手丙', kind: 'artist' },
  { id: 'character-1', name: '角色甲', kind: 'character' }
] };
for (const classified of [true, false]) for (const track of [solo, cast]) for (const type of ['album', 'artist']) {
  for (const destination of destinations(track, type, classified)) {
    assert(values(track, destination.filter, classified).includes(destination.key), 'Destination must contain the selected song');
  }
}
assert.deepEqual(destinations(solo, 'album', true).map(d => d.key), [album], 'Album tags stay intact');
assert.equal(destinations(solo, 'artist', true)[0].key, 'artist-1', 'Use classification ID, not display name');
assert.deepEqual(destinations(cast, 'artist', true).map(d => d.filter), ['characters', 'groups', 'artists']);
assert.deepEqual(destinations(solo, 'artist', false).map(d => d.key), ['A feat. B'], 'Do not split raw artist names');
assert.deepEqual(destinations(null, 'artist', true), []);
assert.deepEqual(destinations({ album: '  ', artist: '' }, 'album', false), []);
assert.deepEqual(destinations({ credits: [] }, 'artist', true), []);
assert.deepEqual(destinations({ credits: [{ id: 'x', kind: 'unknown' }] }, 'artist', true), []);
console.log('PASS: album identity, classified artists, multiple credits, role/group routing, raw names and missing metadata');
