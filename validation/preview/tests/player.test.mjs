import test from 'node:test';
import assert from 'node:assert/strict';
import { Player } from '../src/player.mjs';

test('audio pause does not play queued gestures until speech finishes', () => {
  const p = new Player();
  p.audio('playing');
  p.action('wave');
  assert.equal(p.current, 'speaking');
  p.audio('pause');
  assert.equal(p.current, 'idle');
  p.audio('playing');
  assert.equal(p.current, 'speaking');
  p.audio('ended');
  assert.equal(p.current, 'wave');
});
test('stop clears all pending gestures and late audio events are ignored', () => {
  const p = new Player();
  const old = p.beginAudio();
  p.audio('playing', old);
  p.action('wave');
  p.stop();
  p.audio('playing', old);
  p.audio('ended', old);
  assert.equal(p.current, 'idle');
  assert.equal(p.queue.length, 0);
});
test('ordinary gestures replace each other and finish in idle', () => {
  const p = new Player();
  p.action('wave');
  p.action('nod');
  assert.equal(p.current, 'nod');
  p.complete();
  assert.equal(p.current, 'idle');
});
test('queue is bounded during long audio', () => {
  const p = new Player();
  p.audio('playing');
  for (let n=0; n<100; n++) p.action('wave');
  assert.ok(p.queue.length <= 8);
});
