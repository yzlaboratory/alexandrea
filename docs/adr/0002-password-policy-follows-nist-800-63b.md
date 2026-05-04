# Password policy follows NIST 800-63B

Passwords must be at least 12 characters and must not appear in the
HaveIBeenPwned breach corpus (checked via the k-anonymity API at
account creation and at password reset). There are **no** mandatory
character-class rules — no required upper/lower/digit/symbol — and no
forced rotation.

This deliberately follows NIST 800-63B's modern guidance: long
passphrases plus breach screening dominate classic complexity rules in
both security and usability. A reader expecting the classic rules will
naturally try to "fix" the absence of `must contain a symbol`; this
ADR exists to head that off.
