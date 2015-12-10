# Ammonite [![Build Status](https://travis-ci.org/lihaoyi/Ammonite.svg)](https://travis-ci.org/lihaoyi/Ammonite) [![Join the chat at https://gitter.im/lihaoyi/Ammonite](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/lihaoyi/Ammonite?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

This is where the code for the [Ammonite](https://lihaoyi.github.io/Ammonite) project lives; Both:

- [Ammonite-REPL](https://lihaoyi.github.io/Ammonite), the improved Scala REPL
- [Ammonite-Ops](https://lihaoyi.github.io/Ammonite/#Ammonite-Ops), the Scala file-system library
- [Ammonite-Shell](https://lihaoyi.github.io/Ammonite/#Ammonite-Shell), the Bash-replacement system shell

If you want to learn more about Ammonite or how to use it, check out the links above, or ask on the [Gitter Channel](https://gitter.im/lihaoyi/Ammonite). The remainder of this document is developer-docs for people who want to work on the Ammonite source code itself.

# Developer Docs

The layout of the repository is roughly:

- `ops/` is [Ammonite-Ops](https://lihaoyi.github.io/Ammonite/#Ammonite-Ops)
- `repl/` is [Ammonite-REPL](https://lihaoyi.github.io/Ammonite)
- `shell/` is [Ammonite-Shell](https://lihaoyi.github.io/Ammonite/#Ammonite-Shell)
- `terminal/` is the JLine re-implementation used by Ammonite-REPL to provide syntax highlighting and multiline editing
- `readme/` is the source code for the [Documentation](https://lihaoyi.github.io/Ammonite/#Ammonite-Ops), written in [Scalatex](https://lihaoyi.github.io/Scalatex/).
- `modules/` is a synthetic project used for publishing, purely intended to exclude the readme

## Common Commands

### Manual Testing

Although most features should be unit tested, it's still useful to fire up a REPL from the current codebase to see things work (or not). There are a variety of shells you can spin up for testing different things:

- `sbt ~terminal/test:run` is useful for manual testing the terminal interaction; it basically contains a minimal echo-anything terminal, with multiline input based on the count of open- and closed-parentheses. This lets you test all terminal interactions without all the complexity of the Scala compiler, classloaders, etc. that comes in `repl/`
- `sbt ~repl/test:run` brings up the Ammonite-REPL using the source code in the repository, and automatically restarts it on-exit if you have made a change to the code. Useful for manual testing both of `repl/` as well as `ops/`, since you can just `import ammonite.ops._` and start using them. Note that this does not bring in filesystem utilities like the `wd` variable, `cd!` command.
- `sbt ~shell/test:run` brings up a fully-loaded shell with all filesystem utilities included: `wd`, `cd!`, autocomplete for filesystem paths, and more. This uses `readme/resources/example-predef.scala` instead of your default predef, for easier experimentation and development.
- `sbt ~integration/test:run` runs the trivial main method in the `integration` subproject, letting you manually test running Ammonite programmatically, whether through `run` or `debug`
- `sbt ~integration/test:console` brings up a console in the `integration` subproject, loading Ammonite-REPL as a test console, as described in the readme. Similar to `integration/test:run` but useful for verifying the different classloader/execution environment we get by starting Ammonite inside the Scala REPL doesn't break things

### Automated Testing

While working on a arbitrary `xyz` subproject, `sbt ~xyz/test` runs tests after every change. `repl/test` can be a bit slow because of the amount of code it compiles, so you may want to specify the test manually via `repl/test-only -- ammonite.repl.TestObject.path.to.test`.

- `ops/test` tests the filesystem operations, without any REPL present
- `repl/test` tests the Ammonite-REPL, without filesystem-shell integration.
- `terminal/test` tests the readline re-implementation: keyboard navigation, shortcuts, editing, without any filesystem/scala-repl logic
- `shell/test` tests the integration between the standalone `ops/` and `repl/` projects: features like `cd!`/`wd`, path-completion, ops-related pretty-printing and tools
- `integration/test` kicks off the integration tests, which bundle `repl/` and `shell/` into their respective jars and invoke them as subprocesses. Somewhat slow, but exercises all the command-line-parsing stuff that the other unit tests do not exercise, and makes sure that everything works when run from `.jar`s instead of loose class-files

### Publishing

- `sbt repl/assembly ++2.10.5 repl/assembly` to bundle the REPL as a standalone distribution
- `sbt +modules/publishLocal` or `+sbt modules/publishSigned` is used for publishing.
- `sbt ~readme/run` builds the documentation inside its target folder, which you can view by opening `readme/target/scalatex/index.html` in your browser.
- `git checkout gh-pages && cp -r readme/target/scalatex/* . && git commit -am . && git push` will deploy the generated documentation to Github Pages

## Issue Tags

I've started tagging open issues in the issue tracker to try and keep things neat. This is what the various tags mean:

Each issue should only have one of these:

- `bug`: this behavior clearly wrong, and needs to be fixed
- `enhancement`: something relatively speccable, that can be worked on, finished, and will make Ammonite better
- `wishlist`: could be totally awesome, but we're uncertain if it is worth doing at all, what it would look like, or if it will ever reach a "finished" state.

And possibly:

- `help wanted`: I don't have context, hardware, or for some other reason am unlikely to ever do this. But I know people out there care, so one of you should step up and fix it.

## Contribution Guidelines

- **All code PRs should come with**: a meaningful description, inline-comments for important things, unit tests (positive and negative), and a green build in [CI](https://travis-ci.org/lihaoyi/Ammonite)
- **Try to keep lines below 80 characters width**, with a hard limit of 100 characters.
- **PRs for features should generally come with *something* added to the [Documentation](https://lihaoyi.github.io/Ammonite)**, so people can discover that it exists
- **Be prepared to discuss/argue-for your changes if you want them merged**! You will probably need to refactor so your changes fit into the larger codebase
- **If your code is hard to unit test, and you don't want to unit test it, that's ok**. But be prepared to argue why that's the case!
- **It's entirely possible your changes won't be merged**, or will get ripped out later. This is also the case for my changes, as the Author!
- **Even a rejected/reverted PR is valuable**! It helps explore the solution space, and know what works and what doesn't. For every line in the repo, at least three lines were tried, committed, and reverted/refactored, and more than 10 were tried without committing.
- **Feel free to send Proof-Of-Concept PRs** that you don't intend to get merged.
