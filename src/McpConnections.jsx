import React, { useEffect, useState } from "react";
import { Copy, KeyRound, LoaderCircle, Trash2 } from "lucide-react";
import { api } from "./api";

function displayTime(value) {
  if (!value) return "Never";
  const date = new Date(typeof value === "number" ? value * 1000 : value);
  return date.toLocaleDateString("en-US", {
    timeZone: "Asia/Kolkata",
    month: "short",
    day: "numeric",
    year: "numeric",
  });
}

export default function McpConnections() {
  const [tokens, setTokens] = useState([]);
  const [connections, setConnections] = useState([]);
  const [endpoint, setEndpoint] = useState(window.location.origin + "/mcp");
  const [name, setName] = useState("");
  const [createdToken, setCreatedToken] = useState(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [copied, setCopied] = useState("");
  useEffect(() => {
    let alive = true;
    Promise.all([api("/mcp/info"), api("/mcp/tokens"), api("/mcp/connections")])
      .then(([info, tokenList, appList]) => {
        if (!alive) return;
        setEndpoint(info.url || window.location.origin + "/mcp");
        setTokens(tokenList);
        setConnections(appList);
      })
      .catch((e) => {
        if (alive) setError(e.message);
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, []);

  async function createToken(event) {
    event.preventDefault();
    setBusy(true);
    setError("");
    setCreatedToken(null);
    setCopied("");
    try {
      const result = await api("/mcp/tokens", {
        method: "POST",
        body: { name: name.trim() },
      });
      setCreatedToken(result);
      setTokens(await api("/mcp/tokens"));
      setName("");
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  async function revoke(id, type) {
    setBusy(true);
    setError("");
    try {
      await api(`/mcp/${type}/${id}`, { method: "DELETE" });
      if (type === "tokens") {
        setTokens(await api("/mcp/tokens"));
        if (createdToken?.id === id) setCreatedToken(null);
      } else setConnections(await api("/mcp/connections"));
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  async function copy(value, label) {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(label);
    } catch {
      setError(
        "Copy is unavailable in this browser. Select and copy the text manually.",
      );
    }
  }

  const quotedEndpoint = "'" + endpoint.replaceAll("'", "'\\''") + "'";
  const cli = `claude mcp add --transport http forma ${quotedEndpoint} --header "Authorization: Bearer YOUR_FORMA_TOKEN"`;
  return (
    <section className="mcp-connections" aria-label="MCP connections">
      <h3>
        <KeyRound size={17} /> Connect your agent
      </h3>
      <p>
        Use Forma’s MCP server to get plans and tracking, regenerate up to 28
        days, record completed or skipped activities, and upload progress
        photos.
      </p>
      {loading && (
        <p role="status">
          <LoaderCircle className="spin" size={14} /> Loading connections…
        </p>
      )}
      {error && (
        <p className="feedback-error" role="alert">
          {error}
        </p>
      )}
      <label>
        MCP server URL
        <div className="copy-field">
          <input readOnly value={endpoint} />
          <button
            type="button"
            className="outline"
            onClick={() => copy(endpoint, "Server URL copied")}
            aria-label="Copy MCP server URL"
          >
            <Copy size={15} />
          </button>
        </div>
      </label>
      <p>
        For ChatGPT, deploy Forma at a public HTTPS address and add this URL as
        an MCP connector. Sign in to authorize your account. A local server is
        accessible to agents running on your machine.
      </p>
      <form onSubmit={createToken}>
        <label>
          Connection name
          <input
            value={name}
            onChange={(event) => setName(event.target.value)}
            maxLength={80}
            placeholder="Claude on my laptop"
            required
          />
        </label>
        <button
          className="outline full"
          disabled={busy || loading || !name.trim()}
        >
          {busy ? (
            <LoaderCircle className="spin" size={15} />
          ) : (
            <KeyRound size={15} />
          )}{" "}
          Create connection token
        </button>
      </form>
      {createdToken && (
        <div className="mcp-secret">
          <p>
            Save this token now. It is shown once and grants access to your
            plans, tracking, and photos.
          </p>
          <label>
            New connection token
            <div className="copy-field">
              <input readOnly value={createdToken.token} autoComplete="off" />
              <button
                type="button"
                className="outline"
                onClick={() => copy(createdToken.token, "Token copied")}
                aria-label="Copy connection token"
              >
                <Copy size={15} />
              </button>
            </div>
          </label>
          <button
            type="button"
            className="text-button"
            onClick={() => setCreatedToken(null)}
          >
            Hide token
          </button>
        </div>
      )}
      {copied && <p role="status">{copied}.</p>}
      <details className="mcp-instructions">
        <summary>Claude CLI setup</summary>
        <p>Replace YOUR_FORMA_TOKEN with your connection token, then run:</p>
        <pre>{cli}</pre>
        <button
          type="button"
          className="text-button"
          onClick={() => copy(cli, "Setup command copied")}
        >
          Copy setup command <Copy size={13} />
        </button>
      </details>
      <h4>Connection tokens</h4>
      {tokens.length ? (
        <ul className="mcp-list">
          {tokens.map((token) => (
            <li key={token.id}>
              <div>
                <b>{token.name}</b>
                <small>
                  {token.revoked
                    ? "Revoked"
                    : `Expires ${displayTime(token.expires)}`}{" "}
                  · Last used: {displayTime(token.last_used)}
                </small>
              </div>
              {!token.revoked && (
                <button
                  type="button"
                  className="text-button"
                  aria-label={`Revoke ${token.name}`}
                  disabled={busy}
                  onClick={() => revoke(token.id, "tokens")}
                >
                  <Trash2 size={14} /> Revoke
                </button>
              )}
            </li>
          ))}
        </ul>
      ) : (
        <p>No connection tokens yet.</p>
      )}
      <h4>Authorized apps</h4>
      {connections.length ? (
        <ul className="mcp-list">
          {connections.map((connection) => (
            <li key={connection.id}>
              <div>
                <b>{connection.name}</b>
                <small>
                  {connection.revoked
                    ? "Revoked"
                    : `Connected ${displayTime(connection.created)}`}
                </small>
              </div>
              {!connection.revoked && (
                <button
                  type="button"
                  className="text-button"
                  aria-label={`Revoke ${connection.name}`}
                  disabled={busy}
                  onClick={() => revoke(connection.id, "connections")}
                >
                  <Trash2 size={14} /> Revoke
                </button>
              )}
            </li>
          ))}
        </ul>
      ) : (
        <p>
          No authorized apps yet. Apps appear here after you sign in through an
          MCP connector.
        </p>
      )}
    </section>
  );
}
