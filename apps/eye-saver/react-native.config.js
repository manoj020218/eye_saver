module.exports = {
  project: {
    android: {sourceDir: './android'},
    ios: {sourceDir: './ios'},
  },
  // Local workspace modules are linked explicitly in the Android project.
  // This keeps the example app deterministic even when generated library
  // build directories exist beside source code on Windows.
};
