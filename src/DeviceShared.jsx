import React, { useEffect, useRef, useState } from "react";
import {
  AlertCircle,
  ArrowLeft,
  Check,
  Copy,
  Download,
  LoaderCircle,
  RefreshCw,
  Smartphone,
  Trash2,
  X,
} from "lucide-react";
import { api } from "./api";
import "./device-data.css";

export function DataError({ error, retry }) {
  if (!error) return null;
  return (
    <div className="device-error" role="alert">
      <AlertCircle size={18} />
      <span>{error}</span>
      {retry && (
        <button className="outline" onClick={retry}>
          Try again
        </button>
      )}
    </div>
  );
}

export function DataLoading({ label = "Loading synced data…" }) {
  return (
    <div className="device-loading" role="status">
      <LoaderCircle size={20} className="spin" />
      {label}
    </div>
  );
}

export function useDeviceRequest(path, revision = 0, { poll = false } = {}) {
  const [state, setState] = useState({
    data: null,
    loading: !!path,
    error: "",
  });
  useEffect(() => {
    if (!path) {
      setState({ data: null, loading: false, error: "" });
      return;
    }
    let alive = true;
    let pending = false;
    const controller = new AbortController();
    setState({ data: null, loading: true, error: "" });
    async function load() {
      if (pending) return;
      pending = true;
      try {
        const data = await api(path, { signal: controller.signal });
        if (alive) setState({ data, loading: false, error: "" });
      } catch (error) {
        if (alive)
          setState((previous) => ({
            ...previous,
            loading: false,
            error: error.message,
          }));
      } finally {
        pending = false;
      }
    }
    load();
    const timer = poll
      ? window.setInterval(() => {
          if (document.visibilityState === "visible") load();
        }, 30000)
      : null;
    return () => {
      alive = false;
      controller.abort();
      if (timer) window.clearInterval(timer);
    };
  }, [path, revision, poll]);
  return state;
}

export function RefreshButton({ onClick, loading }) {
  return (
    <button className="outline" disabled={loading} onClick={onClick}>
      <RefreshCw size={15} />
      Refresh
    </button>
  );
}

export function DeviceEmpty({
  title = "Your devices belong here.",
  children,
  action,
}) {
  return (
    <div className="device-empty">
      <Smartphone size={32} />
      <h2>{title}</h2>
      <p>{children}</p>
      {action}
    </div>
  );
}

export function DeviceModal({ title, onClose, children, busy = false }) {
  const dialog = useRef(null);
  useEffect(() => {
    const previous = document.activeElement;
    const element = dialog.current;
    element.showModal();
    element.querySelector("button, input, a")?.focus();
    return () => {
      element.close();
      previous?.focus?.();
    };
  }, []);
  return (
    <dialog
      className="device-modal"
      ref={dialog}
      aria-label={title}
      onCancel={(event) => {
        event.preventDefault();
        if (!busy) onClose();
      }}
      onClick={(event) => {
        if (event.target === event.currentTarget && !busy) onClose();
      }}
    >
      <div className="device-modal-body">
        <button
          className="device-modal-close"
          disabled={busy}
          onClick={onClose}
          aria-label="Close dialog"
        >
          <X size={20} />
        </button>
        <h2>{title}</h2>
        {children}
      </div>
    </dialog>
  );
}

export function DeleteDevice({ device, onDeleted, onCancel }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function remove() {
    setBusy(true);
    setError("");
    try {
      await api(`/devices/${encodeURIComponent(device.device_id)}`, {
        method: "DELETE",
      });
      onDeleted();
    } catch (error) {
      setError(error.message);
      setBusy(false);
    }
  }
  return (
    <DeviceModal
      title={`Remove ${device.name}?`}
      onClose={onCancel}
      busy={busy}
    >
      <p>
        This removes the device and all its synced history and daily summaries
        from your account. Future uploads from this connection will be blocked.
      </p>
      <p>Records stored locally on the phone remain there.</p>
      <DataError error={error} />
      <div className="device-actions">
        <button className="outline" disabled={busy} onClick={onCancel}>
          Keep device
        </button>
        <button className="danger" disabled={busy} onClick={remove}>
          {busy ? (
            <LoaderCircle size={16} className="spin" />
          ) : (
            <Trash2 size={16} />
          )}
          Remove device and history
        </button>
      </div>
    </DeviceModal>
  );
}

export function ConnectDevice({ onClose, account }) {
  const { data, loading, error } = useDeviceRequest("/devices/setup");
  const [copied, setCopied] = useState(false);
  const [copyError, setCopyError] = useState("");
  const origin = data?.server_origin || window.location.origin;
  const downloads = data?.apks || data?.downloads || [];
  async function copy() {
    try {
      await navigator.clipboard.writeText(origin);
      setCopied(true);
      setCopyError("");
    } catch {
      setCopyError("Select and copy the server address below.");
    }
  }
  return (
    <DeviceModal title="Connect an Android device" onClose={onClose}>
      <p>
        Use the collector on your phone to link it to this account. Your device
        appears here after connecting; data appears after the first upload.
      </p>
      {loading && <DataLoading label="Getting connection details…" />}
      <DataError error={error} />
      <ol className="device-setup-steps">
        <li>
          <b>Install Forma Data Sync.</b>
          <p>
            Choose the Android APK matching your phone. The collector can also
            run locally before you connect.
          </p>
          <div className="device-downloads">
            {downloads.map((item) => (
              <a
                className="outline"
                key={item.filename || item.url}
                href={item.url || item.download_url}
                download
              >
                <Download size={15} />
                {item.label || item.name || item.abi || "Android APK"}
                {item.size_bytes
                  ? ` · ${(item.size_bytes / 1000000).toFixed(2)} MB`
                  : ""}
              </a>
            ))}
          </div>
          {!loading && !downloads.length && (
            <small>
              Install the Android APK from your Forma administrator.
            </small>
          )}
        </li>
        <li>
          <b>Sign in on the phone.</b>
          <p>
            Use{" "}
            <strong>{data?.current_account?.email || account?.email}</strong>{" "}
            and your existing Forma password.
          </p>
          <label>
            Server address
            <div className="device-copy-row">
              <input
                readOnly
                aria-label="Server address"
                value={origin}
                onFocus={(event) => event.target.select()}
              />
              <button className="outline" onClick={copy}>
                {copied ? <Check size={16} /> : <Copy size={16} />}
                {copied ? "Copied" : "Copy"}
              </button>
            </div>
          </label>
          {copyError && <small role="status">{copyError}</small>}
          <small>
            Use an address your phone can reach. For a local server, your phone
            and computer must share the network.
          </small>
        </li>
        <li>
          <b>Choose what to share.</b>
          <p>
            Grant the permissions you want, accept upload consent, and tap Sync
            now. Collection and uploads then run periodically on your phone.
          </p>
        </li>
      </ol>
      <button className="primary" onClick={onClose}>
        Done
      </button>
    </DeviceModal>
  );
}

export function BackButton({ children, onClick }) {
  return (
    <button className="device-back" onClick={onClick}>
      <ArrowLeft size={16} />
      {children}
    </button>
  );
}
