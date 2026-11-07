# librashader

Slang shader presets (RetroArch `.slangp`) run through [librashader](https://github.com/SnowflakePowered/librashader).

- `include/librashader.h` is librashader's C API header, verbatim from the tag `librashader-v0.12.1` (C ABI 2,
  API 5; SHA-256 `5d478897c391af3f60015810b67785ae1a286d262a845485276e36ded9f21e62`). The header is MIT licensed
  (`LICENSE`).
- The library itself (`librashader.dll`, `librashader.dylib`, `librashader.so`) is MPL-2.0. It isn't kept here: each
  platform's dependency script (`scripts/deps/build-dependencies-*`, and the Flatpak's `28-librashader` module)
  builds it from the same tag with Rust, and `src/util/librashader_loader.cpp` loads it at run time. Nothing links
  against it, so a build or a system without it runs as before, without slang presets.

The crates (and the C and C++ they carry) compiled into the library have licences of their own, listed in
`data/resources/thirdparty.html` ("librashader's crates: …"). The list is generated with
[cargo-about](https://github.com/EmbarkStudios/cargo-about) (`cargo install --locked cargo-about --features cli`) from a
checkout of the tag, for every target and runtime the builds use (`about.toml`, `about.hbs` here):

```bash
cargo about generate -m librashader-capi/Cargo.toml --no-default-features \
  --features runtime-vulkan,runtime-d3d11,runtime-d3d12,runtime-opengl,runtime-metal \
  -c <this folder>/about.toml <this folder>/about.hbs -o crates.html
```

To update: change the tag in every dependency script and the Flatpak module (and regenerate the module's cargo
sources), copy the tag's `include/librashader.h` here, update the version and hash above, regenerate the crates'
licences into `thirdparty.html`, and check `LIBRASHADER_CURRENT_ABI` and `LIBRASHADER_CURRENT_VERSION` against the
loader.
