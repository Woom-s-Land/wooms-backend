export function fixedReadyRecipients(clients, expectedCount = 10) {
  if (!Array.isArray(clients) || clients.length !== expectedCount) {
    throw new Error('room readiness count does not match the expected subscriber count');
  }
  const ids = clients.map((client) => client.id);
  const expected = new Set(ids);
  for (const client of clients) {
    const seen = client.readyProbeSenders;
    if (!(seen instanceof Set) || seen.size !== expectedCount || [...expected].some((id) => !seen.has(id))) {
      throw new Error('room readiness probe barrier is incomplete');
    }
  }
  for (const method of ['add', 'delete', 'clear']) {
    Object.defineProperty(expected, method, { value() { throw new TypeError('recipient set is immutable'); } });
  }
  return Object.freeze(expected);
}

export function classifyDeliveryTime(receivedAt, measurementStart, measurementEnd, drainEnd) {
  if (receivedAt < measurementStart) return 'beforeMeasurement';
  if (receivedAt < measurementEnd) return 'measurement';
  if (receivedAt < drainEnd) return 'drain';
  return 'lateBeyondDrain';
}

export function isSuccessfulRun({
  attemptedConnections,
  readyConnections,
  liveConnections,
  closedDuringRun,
  schedulerAborted,
  targetAttainment,
  measurementWindowCompleted,
  protocolErrors,
  sendFailures,
  bufferLimitExceeded,
  missingAfterDrain,
  duplicates,
  unexpectedRecipients,
  sequenceReversals,
  unmatchedDeliveries,
  lateBeyondDrain,
}) {
  return attemptedConnections > 0
    && readyConnections === attemptedConnections
    && liveConnections === attemptedConnections
    && closedDuringRun === 0
    && schedulerAborted === false
    && targetAttainment >= 0.98
    && measurementWindowCompleted === true
    && protocolErrors === 0
    && sendFailures === 0
    && bufferLimitExceeded === false
    && missingAfterDrain === 0
    && duplicates === 0
    && unexpectedRecipients === 0
    && sequenceReversals === 0
    && unmatchedDeliveries === 0
    && lateBeyondDrain === 0;
}
