# Connected accounts (Outlook + IMAP): integration notes

New files are complete. I left the shared files below untouched because builders are editing them in parallel and this session has no anchored-edit tool. Each one needs a small additive change, listed below.

## Android
1. **MainActivity.kt, `object Routes`** (next to `MAIL_CONNECTIONS`):
   `const val MAIL_PROVIDERS = "mail/settings/providers"`
2. **MainActivity.kt, NavHost** (after `composable(Routes.MAIL_PLAN) {…}`):
   ```kotlin
   composable(Routes.MAIL_PROVIDERS) {
       com.grapsee.grapmail.ui.screens.mail.ConnectedAccountsScreen(onBack = { nav.popBackStack() })
   }
   ```
3. **MainActivity.kt, `takeDeepLink`**: add this as the first line after `intent ?: return`:
   `if (com.grapsee.grapmail.logic.ProviderOAuth.capture(intent)) { intent.data = null; return }`
   - `onNewIntent` already calls `takeDeepLink`.
   - The ViewModel collects `ProviderOAuth.results`.
   - If the activity was recreated, the result stays in the StateFlow until the screen opens again.
4. **MailSecondaryScreens.kt, `MailSettingsScreen`**:
   - Add the parameter `onProviders: () -> Unit = {}`.
   - After the "Connected accounts" row, add:
     `SettingRow(Icons.Rounded.MailOutline, "Connected mailboxes", "Outlook, Yahoo, iCloud and other IMAP", palette, onClick = onProviders)`
   - At the call site add `onProviders = { nav.navigate(Routes.MAIL_PROVIDERS) },`.
   - The existing "Connected accounts" row is the sign-in apps list (ProfileConnectionDto), so it is a different feature and stays as it is.
5. **AndroidManifest.xml**: in the existing `grapmail` VIEW intent-filter, add
   `<data android:scheme="grapmail" android:host="providers" android:pathPrefix="/callback" />`

## iOS
1. **RootView.swift**: add a route case `.mailProviders`, and in the destination switch add
   `case .mailProviders: ConnectedAccountsView(onBack: { path.removeLast() })`
2. **MailSecondaryViews.swift**: after the "Connected accounts" SettingRow, add a row titled "Connected mailboxes" that appends `.mailProviders`. This needs an `onProviders` closure, the same way `onConnections` is wired.
3. **URL scheme**: `ASWebAuthenticationSession` with `callbackURLScheme: "grapmail"` captures the redirect itself. No Info.plist or DeepLinks.swift change is needed.
4. **XcodeGen**: the new files sit inside the `GrapMail` and `GrapMailTests` sources, so they are picked up automatically.

## What the backend must add (gap found)
- `POST /providers/microsoft/authorize` builds `redirect_uri = ${PUBLIC_URL}/grapmail/providers/oauth-callback` and a state of `${userId}:${uuid}`.
- **No GET handler for `/providers/oauth-callback` exists** in grapmail-providers.ts. The state is not stored either, so the server cannot verify it.
- The backend needs a GET `/grapmail/providers/oauth-callback`. It should answer `302 Location: grapmail://providers/callback?code=…&state=…` (or `?error=…&error_description=…`) and must not consume the code.
- The app then POSTs `/providers/microsoft/connect` with `{code, redirectUri: <the exact redirect_uri from the authorize URL>, providerEmail}`.
- The Azure app registration must list `${PUBLIC_URL}/grapmail/providers/oauth-callback` as a Web redirect URI. Because of the `/grapsee` prefix the apps use, check whether the public path is `/grapsee/grapmail/...`.
- The flow is a confidential client (client_secret), not PKCE. The app checks state on the device against the state in the authorize URL. A server-side state store would be stronger.
- `providerEmail` is typed in by the user and is not checked against the Microsoft profile. This is a backend trust gap; the first sync is said to verify it.
