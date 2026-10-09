module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  testMatch: ['**/src/test/**/*.test.ts'],
  // Emulator-backed suites run in parallel against one cold Firestore emulator; the first call in each suite
  // (module load, rules load, emulator warm-up) regularly exceeds Jest's 5s default, for tests AND hooks.
  testTimeout: 30000,
};
