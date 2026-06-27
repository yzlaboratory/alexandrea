/**
 * Camp 2 — the Tim Pope / Chris Beams "well-crafted prose" school.
 *
 * A commit is a narrative artifact for the future reader doing `git log` /
 * `git blame`, not structured data for a release robot. So there is no
 * `type(scope):` grammar: the subject is a short capitalised imperative line,
 * and the body (wrapped at 72) explains *why*, not how.
 *
 * The seven rules from https://cbea.ms/git-commit/ and their enforcement here:
 *   1. Separate subject from body with a blank line   -> body-leading-blank
 *   2. Limit the subject line to 50 characters         -> header-max-length
 *   3. Capitalise the subject line                     -> subject-capitalised
 *   4. Do not end the subject line with a period       -> subject-full-stop
 *   5. Use the imperative mood in the subject line     -> subject-imperative-mood (heuristic, warn)
 *   6. Wrap the body at 72 characters                  -> body-max-line-length
 *   7. Use the body to explain what and why            -> convention, not lintable
 *
 * Rule 5 is the only one a linter cannot truly verify (grammar is semantic),
 * so it is a warning-level heuristic that catches the common past-tense /
 * third-person openings and nothing more.
 */
module.exports = {
  // No `extends: '@commitlint/config-conventional'` — Camp 2 has no commit
  // types. Re-parse the header so the whole line is the `subject`; otherwise
  // the default parser expects `type: subject` and leaves subject null.
  parserPreset: {
    parserOpts: {
      headerPattern: /^(.*)$/,
      headerCorrespondence: ['subject'],
    },
  },
  plugins: [
    {
      rules: {
        // Rule 3 — first character must be a capital. cbeams says "capitalise
        // the subject", which is exactly this. (commitlint's built-in
        // `sentence-case` would also lower-case the rest and so reject
        // acronyms like "Add OAuth login" — we don't want that.)
        'subject-capitalised': ({ subject }) => [
          /^[A-Z]/.test((subject || '').trim()),
          'subject must start with a capital letter',
        ],
        // Rule 5 — nudge toward imperative mood ("Add x", not "Added"/"Adds").
        // A linter cannot parse grammar, so this only flags the most common
        // past-tense (-ed) and gerund (-ing) openings, with a small whitelist
        // for real imperatives that end the same way. Warn only; never blocks.
        'subject-imperative-mood': ({ subject }) => {
          const first = ((subject || '').trim().split(/\s+/)[0] || '').toLowerCase();
          const whitelist = /^(bring|ring|sing|string|spring|embed|feed|seed|speed|proceed|exceed|succeed|breed|bleed|need)$/;
          const looksPast = /(ed|ing)$/.test(first) && !whitelist.test(first);
          return [
            !looksPast,
            'subject should be imperative mood ("Add x", not "Added x")',
          ];
        },
      },
    },
  ],
  rules: {
    'subject-empty': [2, 'never'],
    'subject-capitalised': [2, 'always'],
    'subject-full-stop': [2, 'never', '.'], // rule 4
    'header-max-length': [2, 'always', 50], // rule 2
    'body-leading-blank': [2, 'always'], // rule 1
    'footer-leading-blank': [2, 'always'],
    'body-max-line-length': [2, 'always', 72], // rule 6
    'subject-imperative-mood': [1, 'always'], // rule 5 (heuristic)
  },
  helpUrl: 'https://cbea.ms/git-commit/',
};
