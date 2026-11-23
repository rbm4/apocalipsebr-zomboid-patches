# Experimental integration features

This package is test-only. Everything here is experimental and must be treated
as unsuitable for production until a separate promotion task validates it.

Keep feature logic and instrumentation under `worldsimulation` and its
`features` subpackages. Use the isolated original-JAR server fixture and the
test observer agent. Do not register these features in AgentModules, ship them
in the production JAR, or enable them on a live server. Record the exact native
behavior exercised, compatibility losses and benchmark limits before proposing
promotion.
