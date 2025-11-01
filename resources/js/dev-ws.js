(function () {
    const ROUTE = "/dev/reload-ws";

    // Exponential backoff reconnect (cap ~30s)
    let retry = 0;
    let socket = null;

    function wsUrl(route) {
      const { protocol, host } = window.location;
      const wsProto = protocol === "https:" ? "wss:" : "ws:";
      return `${wsProto}//${host}${route}`;
    }

    function connect() {
      const url = wsUrl(ROUTE);
      socket = new WebSocket(url);

      socket.addEventListener("open", () => {
        retry = 0;
        // Optional: identify client or ping
        // socket.send(JSON.stringify({ type: "hello" }));
      });

      socket.addEventListener("message", (ev) => {
        try {
          const data = typeof ev.data === "string" ? ev.data : "";
          // Accept plain "reload" or JSON { type: "reload", delay: <ms> }
          if (data.trim().toLowerCase() === "reload") {
            window.location.reload();
            return;
          }
          const obj = JSON.parse(data);
          if (obj && (obj.type === "reload" || obj.command === "reload")) {
            const delay = obj.delay || 0;
            if (delay > 0) {
              setTimeout(() => window.location.reload(), delay);
            } else {
              window.location.reload();
            }
          }
        } catch {
          // Ignore malformed messages
        }
      });

      socket.addEventListener("close", () => {
        scheduleReconnect();
      });

      socket.addEventListener("error", () => {
        // Let close handler manage reconnect
        try {
          socket.close();
        } catch {}
      });
    }

    function scheduleReconnect() {
      // Backoff: 0.5s, 1s, 2s, 4s, 8s, 16s, 30s...
      retry += 1;
      const delay = Math.min(30000, 500 * Math.pow(2, retry - 1));
      setTimeout(connect, delay);
    }

    // Clean up on unload
    window.addEventListener("beforeunload", () => {
      try {
        socket && socket.close();
      } catch {}
    });

    connect();
  })();