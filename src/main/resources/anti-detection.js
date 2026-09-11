(() => {
    "use strict";
    /* -------------------------------------------------------
     * 1. 保存原生 Function.prototype.toString
     * ----------------------------------------------------- */
    const nativeFunctionToString = Function.prototype.toString;

    /* -------------------------------------------------------
     * 2. WeakMap：函数 → 伪原生源码
     * ----------------------------------------------------- */
    const nativeSourceMap = new WeakMap();

    /* -------------------------------------------------------
     * 3. 注册伪原生源码
     * ----------------------------------------------------- */
    const registerNativeSource = (fn, source) => {
      try {
        nativeSourceMap.set(fn, source);
      } catch (_) {}
    };

    /* -------------------------------------------------------
     * 4. 劫持 Function.prototype.toString
     * ----------------------------------------------------- */
    Object.defineProperty(Function.prototype, "toString", {
      configurable: true,
      writable: true,
      value: function toString() {
        if (nativeSourceMap.has(this)) {
          return nativeSourceMap.get(this);
        }
        return nativeFunctionToString.call(this);
      },
    });

    /* -------------------------------------------------------
     * 5. 伪装 Function.prototype.toString 自身
     * ----------------------------------------------------- */
    registerNativeSource(
      Function.prototype.toString,
      nativeFunctionToString.toString(),
    );

    /* -------------------------------------------------------
     * 6. stealthify：包装函数但保持“原生外观”
     * ----------------------------------------------------- */
    const stealthify = (obj, prop, handler) => {
      const original = obj[prop];
      if (typeof original !== "function") return;

      const wrapped = function (...args) {
        return handler.call(this, original, args);
      };
      const namePropertyDescriptor = Object.getOwnPropertyDescriptor(
        wrapped,
        "name",
      );
      // 处理函数 name 属性
      Object.defineProperty(wrapped, "name", {
        ...namePropertyDescriptor,
        value: prop,
      });
      // 保留 prototype（某些函数有）
      try {
        Object.setPrototypeOf(wrapped, Object.getPrototypeOf(original));
      } catch (_) {}

      // 注册伪原生源码（直接复用原函数的 native 表现）
      registerNativeSource(wrapped, nativeFunctionToString.call(original));

      // 用 defineProperty 保持 descriptor 接近原生
      const desc = Object.getOwnPropertyDescriptor(obj, prop);
      Object.defineProperty(obj, prop, {
        ...desc,
        value: wrapped,
      });
    };

    /* -------------------------------------------------------
     * 7. 示例：stealth console.log / debug / info
     * ----------------------------------------------------- */
    const filterConsoleArgs = (args) =>
      args.map((arg) => {
        if (arg && typeof arg === "object") {
          // 防止 getter / Proxy / 大对象触发
          return {};
        }
        return arg;
      });

    ["log", "debug", "info", "warn", "error", "dir", "table", "debug"].forEach(
      (name) => {
        stealthify(console, name, (original, args) => {
          // ❗不传递原始对象，避免 DevTools / CDP 展开
          return original.apply(console, filterConsoleArgs(args));
        });
      },
    );

    /* -------------------------------------------------------
     * 8. 防御性补丁（可选但强烈建议）
     * ----------------------------------------------------- */

    // 防止检测 toString 被替换
    registerNativeSource(
      registerNativeSource,
      "function registerNativeSource() { [native code] }",
    );

    /* -------------------------------------------------------
     * 9. navigator.webdriver
     *
     * 自动化浏览器里该属性为 true，是成本最低、命中率最高的检测项，
     * 任何风控脚本都会先查它。
     *
     * 注意：这段补丁原本写在 PlaywrightUtil.initStealth() 里，
     * 但那个方法全项目从未被调用，等于没写。现在挪到这里，
     * 因为本文件是 PlaywrightManager 真正注入的脚本。
     * ----------------------------------------------------- */
    try {
      Object.defineProperty(navigator, "webdriver", {
        configurable: true,
        get: () => undefined,
      });
    } catch (_) {}

    /* -------------------------------------------------------
     * 10. 清理 ChromeDriver 注入的全局变量
     *
     * 形如 cdc_adoQpoasnfa76pfcZLmcfl_Array 的变量是 ChromeDriver 的指纹，
     * 属于常见检测清单里的一项。逐个写死名字容易漏，按前缀扫一遍更稳。
     * ----------------------------------------------------- */
    try {
      for (const key of Object.getOwnPropertyNames(window)) {
        if (key.indexOf("cdc_") === 0) {
          try {
            delete window[key];
          } catch (_) {}
        }
      }
    } catch (_) {}

    /* 说明：刻意不伪造 navigator.plugins / window.chrome。
     * 把 plugins 改成 [1,2,3] 这类假值反而比真实值更可疑，
     * 而 headful Chromium 本来就有真实的 window.chrome，不需要补。
     * navigator.languages 也不用改，上下文已设 locale=zh-CN，天然就是中文。 */
  })();