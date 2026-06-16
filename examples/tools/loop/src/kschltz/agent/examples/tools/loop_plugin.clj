(ns kschltz.agent.examples.tools.loop-plugin
  "Legacy namespace kept for backward compatibility.

   The tool-calling loop has been promoted into the core lateralus base
   chain (`kschltz.agent.loop`). The example tools (`time/now` and
   `calculator/eval`) now live in `kschltz.agent.tools.examples` and are
   exposed as the Integrant component `:lateralus/example-tools`.

   See `examples/tools/loop/config.edn` for current usage.")
