# Client startup skips

The client-menu agent module can remove the vanilla startup `TISLogoState` before
the initial `GameStateMachine.update()` selects its first state. This skips both
the Indie Stone splash and the following attribution logos, allowing vanilla to
enter the next startup state immediately. Terms of Service and the main menu
remain in the original order.

The hook is client-only at runtime and only acts on `GameWindow.states` while its
initial state selection is pending. It does not affect server state machines,
later state-machine transitions, or logo states outside the initial slot.

The feature is enabled by default with the client-menu module. Use
`-Dapocbr.clientMenu.skipLogos=false` to retain the logos. The existing
`-Dapocbr.clientMenu.enabled=false` switch disables the module and all of its
hooks.

Validation uses a forked fixture against the original 42.22.2 archive. It checks
the transformed vanilla `GameStateMachine.update()` entry point and confirms the
next startup state becomes current without entering the logo state. This does
not replace a live client startup check.

The photosensitivity warning has its own hook on
`GameWindow.doEpilepsyWarningText()`. The client skips it by default. Set
`-Dapocbr.clientMenu.skipPhotosensitivityWarning=false` to restore it. The
warning setting and logo setting are independent. The forked fixture checks the
warning's default skip, explicit opt-out and dedicated server guard on the
transformed original method.
