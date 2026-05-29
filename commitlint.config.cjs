/**
 * Conventional Commits + a tiny extension for the repo's existing voice.
 *
 * The ADR commits in this repo use shapes like:
 *   adr(0014): security-tokens bullet no longer claims a single token shape
 *   adr(0021)+context+adr(0014,0016,0017): auth moves in-app, kiraauth retired
 *
 * The 'adr' type and an open scope policy keep that history valid.
 */
module.exports = {
  extends: ['@commitlint/config-conventional'],
  rules: {
    'type-enum': [
      2,
      'always',
      [
        'feat',
        'fix',
        'docs',
        'style',
        'refactor',
        'perf',
        'test',
        'build',
        'ci',
        'chore',
        'revert',
        'adr',
        'scaffold',
        'tooling',
      ],
    ],
    'scope-empty': [0],
    'scope-case': [0],
    'subject-case': [0],
    'header-max-length': [2, 'always', 100],
  },
};
