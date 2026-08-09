-- AuthenticatedUser started implementing AuthenticatedPrincipal so sessions
-- index by a stable user id instead of the record's default toString(). Any
-- session issued before this change is indexed under that old toString()
-- value and would silently survive a reset-password sweep that now looks
-- sessions up by user id — clearing every live session forces a one-time
-- re-login instead of leaving stale-indexed rows behind.
DELETE FROM SPRING_SESSION;
