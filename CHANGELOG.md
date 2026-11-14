# Changelog

## 1.3.0 — 2026-11-14

- Expand the plugin catalog to 54 Worker tools (59 with the Bridge lifecycle
  tools) by adding 14 tools. Existing search, patch, and script tools are
  unchanged.
- Add class authoring tools: `add_class` creates a primary JVM class with a
  generated default constructor, supertype, interfaces, and classfile version;
  `duplicate_class` copies a JVM or DEX class under a new name and rewrites its
  self references.
- Add class-level attribute editing with `set_class_attributes` for access
  flags, superclass, interfaces (replace/add/remove), signature, source file,
  and classfile version. `set_field_constant` sets or clears a field
  `ConstantValue` for static final fields.
- Add annotation editing on both formats: `add_annotation`,
  `remove_annotation`, `list_annotations`, and `search_annotations` accept class,
  field, or method targets, visibility, and typed element values.
- Add native Android/DEX member editing: `add_android_member` with generated
  stub bodies and optional `static_value`, `remove_android_member`, and
  `set_android_member_access`, which removes and re-adds the member so code,
  annotations, and signatures survive the access change.
- Add `list_packages` for grouped class counts, `validate_workspace` for parse
  failures, name mismatches, duplicate members, and codeless methods, and
  `extract_entry` for writing a classfile, single-class DEX, or file entry to an
  absolute path.
- DEX class access flags cannot be replaced because the native DEX tree exposes
  no class-access setter; `set_class_attributes` rejects that request and
  directs callers to `run_recaf_script`. Superclass, interfaces, signature, and
  source file edits still apply to DEX classes.

## 1.2.0 — 2026-11-14

- Expand the plugin catalog from 21 to 39 Worker tools (45 with Bridge
  lifecycle tools). New read tools cover structural class metadata, member
  listings, inheritance relationships, installed decompilers, and raw method
  bytecode.
- Add workspace mutation tools for adding and replacing archive entries,
  deleting files and classes, attaching and detaching supporting resources, and
  renaming JVM package prefixes.
- Add JVM member editing through ASM: add fields with optional constant values,
  add methods with generated default-return bodies, remove members, and replace
  member access flags. Android/DEX member edits still use
  `patch_instructions` or `run_recaf_script`.
- Add `search_numbers` for equality, comparison, range, and any-of numeric
  searches across JVM and Android scopes.
- Add `set_decompiler` to switch the session default JVM or Android decompiler.

## 1.1.0 — 2026-10-18

- Save APK and standalone DEX workspaces in their original format, including
  automatic saves and recovery. APK outputs are unsigned and require signing.
- Edit Android method bodies with Dalvik Jasm, exact DEX selection, previews,
  stale-assembly rejection, and preservation of supported debug information.
- Batch native DEX edits through `run_recaf_script`, with Android workspace
  guidance, runnable instruction examples, invoke-range API details, and observed
  change reports across primary DEX bundles. Clarify workspace lifecycle flags.
- Report bounded runtime exception chains, script stack frames, and root causes
  while retaining the outer exception fields.
- Preserve compact signed and floating-point DEX values, nested encoded values,
  and explicit inner-class flags. Validate exported declarations, static values,
  and supported debug tables before installing output files.
- Update class and member-owner references across primary DEX bundles during
  renaming and mapping. Mapping preparation failures leave the workspace unchanged.
- Correct primitive-array conversion for Android source tools and verify detached
  JVM views before decompilation. Saved DEX method bodies remain independent of
  these source views.
- Add `search_structure` for JVM and Android classes and members.
- Add explicit JVM transformation aliases, typed options, ordered phases, and
  previews on detached snapshots. Android-only transformation requests return
  an unsupported-operation error.
- Report runtime and compile-time Recaf identities separately, check required APIs,
  and reject runtime changes between discovery and Worker startup.
- Gate releases on real Worker integration tests against the pinned Recaf build;
  add scheduled compatibility checks against upstream Recaf.

Both components use version 1.1.0. The supported baseline remains
[Recaf `17216bd3`](https://github.com/Col-E/Recaf/commit/17216bd3b76bc6ef36c4198262b5318e2936fbb7).
For upgrade instructions, see [README.md](README.md#install) or
[README-ZH.md](README-ZH.md#安装).
