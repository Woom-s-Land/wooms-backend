import test from 'node:test';
import assert from 'node:assert/strict';
import { classifyDeliveryTime, fixedReadyRecipients, isSuccessfulRun } from '../metrics.mjs';

test('fixed fanout retains the barrier recipients and rejects mutation', () => {
  const ids = Array.from({ length: 10 }, (_, index) => `user-${index}`);
  const clients = ids.map((id) => ({ id, readyProbeSenders: new Set(ids) }));
  const expected = fixedReadyRecipients(clients);
  clients[3].readyProbeSenders.clear(); // later connection state does not mutate the fixed expected set
  assert.equal(expected.size, 10);
  assert.equal(expected.has('user-3'), true);
  assert.throws(() => expected.delete('user-3'), /immutable/);
});

test('delivery deadlines use a half-open drain interval', () => {
  assert.equal(classifyDeliveryTime(9, 10, 20, 30), 'beforeMeasurement');
  assert.equal(classifyDeliveryTime(10, 10, 20, 30), 'measurement');
  assert.equal(classifyDeliveryTime(20, 10, 20, 30), 'drain');
  assert.equal(classifyDeliveryTime(29.999, 10, 20, 30), 'drain');
  assert.equal(classifyDeliveryTime(30, 10, 20, 30), 'lateBeyondDrain');
});

test('observed application failures make ok false without discarding the result', () => {
  const sample = {
    attemptedConnections: 1000, readyConnections: 1000, liveConnections: 999,
    closedDuringRun: 1, schedulerAborted: true, targetAttainment: 0.5,
    measurementWindowCompleted: true, protocolErrors: 0, sendFailures: 0,
    bufferLimitExceeded: false, missingAfterDrain: 4, duplicates: 0,
    unexpectedRecipients: 0, sequenceReversals: 0, unmatchedDeliveries: 0,
    lateBeyondDrain: 0,
  };
  assert.equal(isSuccessfulRun(sample), false);
  assert.equal(sample.missingAfterDrain, 4);
});
