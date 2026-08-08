import { type ReactNode, useState } from 'react';
import {
  AppBar,
  Box,
  Button,
  Container,
  Menu,
  MenuItem,
  Tab,
  Tabs,
  Toolbar,
} from '@mui/material';
import {
  Link as RouterLink,
  Outlet,
  useNavigate,
  useParams,
} from 'react-router-dom';
import { logout, switchMediaType } from '../auth/authApi';
import { useSession } from '../auth/SessionContext';

const MEDIA_TYPES = ['movies', 'tv', 'books', 'games'] as const;
const SURFACES = ['watchlist', 'library'] as const;

type MediaType = (typeof MEDIA_TYPES)[number];
type Surface = (typeof SURFACES)[number];

const MEDIA_TYPE_LABELS: Record<MediaType, string> = {
  movies: 'Movies',
  tv: 'TV',
  books: 'Books',
  games: 'Games',
};

const SURFACE_LABELS: Record<Surface, string> = {
  watchlist: 'Watchlist',
  library: 'Library',
};

function isMediaType(value: string | undefined): value is MediaType {
  return MEDIA_TYPES.includes(value as MediaType);
}

function isSurface(value: string | undefined): value is Surface {
  return SURFACES.includes(value as Surface);
}

// This shell also wraps /account (no :mediaType/:surface in that URL at all),
// plus a stale bookmark or typo could put an unrecognized segment in either
// param — neither should crash the tabs, so each dimension falls back to a
// sane default for the *other* tab row's links.
function AppShell(): ReactNode {
  const params = useParams<{ mediaType: string; surface: string }>();
  const mediaType = params.mediaType;
  const surface = params.surface;
  const navigate = useNavigate();
  const { user, refresh } = useSession();
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null);

  async function handleLogOut(): Promise<void> {
    setMenuAnchor(null);
    await logout();
    await refresh();
    void navigate('/');
  }

  async function handleMediaTypeChange(type: MediaType): Promise<void> {
    await switchMediaType(type);
    await refresh();
  }

  return (
    <Box>
      <AppBar position="static" color="default" elevation={0}>
        <Toolbar sx={{ justifyContent: 'space-between' }}>
          <Tabs value={isMediaType(mediaType) ? mediaType : false}>
            {MEDIA_TYPES.map((type) => (
              <Tab
                key={type}
                label={MEDIA_TYPE_LABELS[type]}
                value={type}
                component={RouterLink}
                to={`/${type}/${isSurface(surface) ? surface : 'watchlist'}`}
                onClick={() => void handleMediaTypeChange(type)}
              />
            ))}
          </Tabs>

          <Button
            onClick={(event) => {
              setMenuAnchor(event.currentTarget);
            }}
            aria-haspopup="true"
          >
            {user?.email ?? 'Account'}
          </Button>
          <Menu
            anchorEl={menuAnchor}
            open={menuAnchor !== null}
            onClose={() => {
              setMenuAnchor(null);
            }}
          >
            <MenuItem
              component={RouterLink}
              to="/account"
              onClick={() => {
                setMenuAnchor(null);
              }}
            >
              Account settings
            </MenuItem>
            <MenuItem onClick={() => void handleLogOut()}>Log out</MenuItem>
          </Menu>
        </Toolbar>

        <Tabs value={isSurface(surface) ? surface : false} sx={{ px: 2 }}>
          {SURFACES.map((s) => (
            <Tab
              key={s}
              label={SURFACE_LABELS[s]}
              value={s}
              component={RouterLink}
              to={`/${isMediaType(mediaType) ? mediaType : 'movies'}/${s}`}
            />
          ))}
        </Tabs>
      </AppBar>

      <Container maxWidth="md" sx={{ py: 6 }}>
        <Outlet />
      </Container>
    </Box>
  );
}

export default AppShell;
