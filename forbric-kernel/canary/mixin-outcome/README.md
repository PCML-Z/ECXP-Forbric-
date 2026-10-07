# M36 final Mixin application and late decisions

The NeoForge canary first loads a plain target class from the first live server tick. Its selected Mixin
has a working HEAD injector and an INVOKE injector whose target does not exist. The ordinary guest config
uses original `defaultRequire: 1`; the loader's existing relaxation permits the class to be defined. The
final-definition audit must identify the exact absent handler using Mixin's real rename metadata.

`gate-m36-mixin-outcome.sh` builds and runs six isolated, hash-bound instances:

- Required + strict: the mod does not finish loading, and since 9fccb0c that needs a decision like a FAILED one,
  so STRICT refuses the launch while the mods are loading. Nothing is created: the server never starts, no world
  is reached, no guest handler runs, no crash report is written, and the report still names exactly the
  necessary missing handler. Nothing may reach a tick boundary.
- Required + explicit continue: the third tick occurs; the confirmed required finding remains in the report.
- Optional: the missing injector explicitly declares `require = 0`; strict mode reaches the third tick.
- Declined: the mod's own plugin turns off both Mixins; strict mode reaches the third tick with no invented loss.

The canary neither calls a kernel diagnostic API nor manufactures a compatibility finding. Its ordinary
classes live outside the dedicated Mixin package. The config name is a regular guest name, because names
reserved for the kernel are intentionally not relaxed. Startup failure cannot satisfy any case.

The final two cases use a single-argument ModifyArg whose explicit index still refers to the same argument
after a carrier appends a context parameter. The real target must observe `changed|context`; with widening
off it observes `initial|context` and retains a required missing-injector finding, which STRICT again refuses
at load. This verifies both the modified argument and preservation of the added context, not just an
annotation edit.
