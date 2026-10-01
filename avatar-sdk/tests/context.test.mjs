import assert from 'node:assert/strict';
import { test } from 'node:test';
import { webcrypto } from 'node:crypto';
import { PageContext } from '../dist/context.js';

test('explicit Element capture excludes private and invisible ancestors and rate limits repeated capture', async () => {
  globalThis.crypto ??= webcrypto;
  globalThis.scrollX = globalThis.scrollY = 0;
  globalThis.innerWidth = 800; globalThis.innerHeight = 600;
  globalThis.NodeFilter = { SHOW_TEXT: 4 };
  const box = { left: 0, top: 0, right: 400, bottom: 200 };
  const element = (privateField = false, opacity = '1', parentElement = null) => ({
    tagName: 'P', parentElement, opacity, isConnected: true,
    getClientRects: () => [box], getBoundingClientRect: () => box,
    contains: () => true, closest: selector => privateField && selector === '[data-ln-private]' ? {} : null,
  });
  const root = element();
  const publicNode = { textContent: 'visible', parentElement: element(false, '1', root), isConnected: true };
  const privateNode = { textContent: 'secret', parentElement: element(true, '1', root), isConnected: true };
  const invisibleNode = { textContent: 'hidden', parentElement: element(false, '1', element(false, '0', root)), isConnected: true };
  const offscreenNode = { textContent: 'offscreen', parentElement: root, rects: [{left:0,top:-100,right:400,bottom:-80}], isConnected:true };
  globalThis.location = { href: 'https://demo.invalid/', origin: 'https://demo.invalid' };
  globalThis.getComputedStyle = value => ({ display: 'block', visibility: 'visible', opacity: value.opacity });
  globalThis.document = {
    body: root, documentElement: { scrollWidth: 800, scrollHeight: 600 },
    querySelector: () => root,
    createRange: () => ({selectNodeContents(node) {this.node=node;}, getClientRects() {return this.node.rects ?? [box];}}),
    createTreeWalker: () => {
      let index = -1; const nodes = [publicNode, privateNode, invisibleNode, offscreenNode];
      return { nextNode() { index++; return index < nodes.length; }, get currentNode() { return nodes[index]; } };
    },
  };
  const context = new PageContext();
  const result = await context.captureLocal({ source: 'ELEMENT', elementSelector: '#sample', screenshot: false });
  assert.equal(result.metadata.dom.text, 'visible');
  assert.equal(result.metadata.dom.excludedCount, 3);
  assert.equal(result.metadata.screenshot.status, 'NOT_REQUESTED');
  assert.equal(result.images.length, 0);
  await assert.rejects(context.captureLocal({ source: 'ELEMENT', elementSelector: '#sample', screenshot: false }), /CONTEXT_RATE_LIMIT/);
  context.clear();
});
