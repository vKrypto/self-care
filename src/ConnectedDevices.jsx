import React, { useEffect, useState } from "react";
import {
  ArrowRight,
  CalendarDays,
  CheckCircle2,
  Download,
  HeartPulse,
  Layers,
  Plus,
  Smartphone,
  Trash2,
} from "lucide-react";
import { api } from "./api";
import { formatBytes, formatTime, sourceLabel } from "./deviceData";
import {
  BackButton,
  ConnectDevice,
  DataError,
  DataLoading,
  DeleteDevice,
  DeviceEmpty,
  RefreshButton,
  useDeviceRequest,
} from "./DeviceShared";

function SyncFacts({ device, timezone }) {
  return (
    <dl className="device-facts">
      <div>
        <dt>First synced</dt>
        <dd>{formatTime(device.first_synced_at_ms, timezone)}</dd>
      </div>
      <div>
        <dt>Last synced</dt>
        <dd>{formatTime(device.last_synced_at_ms, timezone)}</dd>
      </div>
      <div>
        <dt>Successful syncs</dt>
        <dd>{(device.sync_count || 0).toLocaleString()}</dd>
      </div>
    </dl>
  );
}

export default function ConnectedDevices({
  account,
  timezone = "Asia/Kolkata",
  selectedDeviceId = null,
  onWellbeing,
  onRemoved,
}) {
  const [deviceId, setDeviceId] = useState(selectedDeviceId);
  const [connect, setConnect] = useState(false);
  const [revision, setRevision] = useState(0);
  const state = useDeviceRequest("/devices", revision, { poll: true });
  const devices = state.data?.devices || [];
  const count = devices.reduce(
    (total, device) => total + (device.sync_count || 0),
    0,
  );
  if (deviceId)
    return (
      <DeviceDetails
        key={deviceId}
        deviceId={deviceId}
        timezone={timezone}
        onBack={() => setDeviceId(null)}
        onWellbeing={onWellbeing}
        onRemoved={() => {
          setDeviceId(null);
          setRevision((value) => value + 1);
          onRemoved?.();
        }}
      />
    );
  return (
    <div className="device-page">
      <div className="page-heading">
        <div className="eyebrow">YOUR CONNECTED LIFE</div>
        <div className="heading-row">
          <div>
            <h1>Connected devices</h1>
            <p>Your phones, their syncs, and the data you have shared.</p>
          </div>
          <div className="device-actions">
            <RefreshButton
              loading={state.loading}
              onClick={() => setRevision((value) => value + 1)}
            />
            <button className="primary" onClick={() => setConnect(true)}>
              <Plus size={17} />
              Connect new device
            </button>
          </div>
        </div>
      </div>
      <DataError
        error={state.error}
        retry={() => setRevision((value) => value + 1)}
      />
      {state.loading ? (
        <DataLoading />
      ) : (
        <>
          <div className="device-summary-strip">
            <span>
              <Smartphone size={17} />
              {devices.length} connected{" "}
              {devices.length === 1 ? "device" : "devices"}
            </span>
            <span>
              <CheckCircle2 size={17} />
              {count.toLocaleString()} successful syncs
            </span>
            <span>Updates appear automatically after upload.</span>
          </div>
          {devices.length ? (
            <div className="device-grid">
              {devices.map((device) => (
                <button
                  className="device-card"
                  key={device.device_id}
                  onClick={() => setDeviceId(device.device_id)}
                  aria-label={`View ${device.name}`}
                >
                  <div className="device-card-heading">
                    <span className="device-icon">
                      <Smartphone size={24} />
                    </span>
                    <span>
                      <h2>{device.name}</h2>
                      <small>
                        Android · Connected{" "}
                        {formatTime(device.registered_at_ms, timezone)}
                      </small>
                    </span>
                    <ArrowRight size={20} />
                  </div>
                  <SyncFacts device={device} timezone={timezone} />
                  <div className="device-card-foot">
                    <span>
                      {device.retained_raw_batch_count || 0} syncs with viewable
                      records
                    </span>
                    <b>
                      View complete history <ArrowRight size={14} />
                    </b>
                  </div>
                </button>
              ))}
            </div>
          ) : (
            <DeviceEmpty
              action={
                <button className="primary" onClick={() => setConnect(true)}>
                  <Plus size={16} />
                  Connect new device
                </button>
              }
            >
              Connect your Android phone to see its synced data and daily
              activity here.
            </DeviceEmpty>
          )}
        </>
      )}
      {connect && (
        <ConnectDevice
          account={account}
          onClose={() => {
            setConnect(false);
            setRevision((value) => value + 1);
          }}
        />
      )}
    </div>
  );
}

function DeviceDetails({ deviceId, timezone, onBack, onWellbeing, onRemoved }) {
  const [revision, setRevision] = useState(0);
  const [batchId, setBatchId] = useState(null);
  const [offset, setOffset] = useState(0);
  const [remove, setRemove] = useState(false);
  const detail = useDeviceRequest(
    `/devices/${encodeURIComponent(deviceId)}`,
    revision,
    { poll: true },
  );
  const history = useDeviceRequest(
    `/devices/${encodeURIComponent(deviceId)}/batches?limit=20&offset=${offset}`,
    revision,
    { poll: true },
  );
  const device = detail.data?.device;
  if (batchId)
    return (
      <BatchDetails
        deviceId={deviceId}
        batchId={batchId}
        timezone={timezone}
        onBack={() => setBatchId(null)}
      />
    );
  return (
    <div className="device-page">
      <BackButton onClick={onBack}>All devices</BackButton>
      <div className="page-heading">
        <div className="eyebrow">DEVICE HISTORY</div>
        <div className="heading-row">
          <div>
            <h1>{device?.name || "Your device"}</h1>
            <p>
              Every retained sync, with the original records and source details.
            </p>
          </div>
          <div className="device-actions">
            <RefreshButton
              loading={detail.loading || history.loading}
              onClick={() => setRevision((value) => value + 1)}
            />
            <button className="outline" onClick={() => onWellbeing(deviceId)}>
              <HeartPulse size={16} />
              Digital wellbeing
            </button>
            {device && (
              <button className="danger" onClick={() => setRemove(true)}>
                <Trash2 size={15} />
                Remove device
              </button>
            )}
          </div>
        </div>
      </div>
      <DataError
        error={detail.error || history.error}
        retry={() => setRevision((value) => value + 1)}
      />
      {detail.loading ? (
        <DataLoading />
      ) : (
        device && (
          <section className="device-panel">
            <div className="device-panel-heading">
              <Smartphone size={22} />
              <h2>{device.name}</h2>
              <span className="device-badge">Android</span>
            </div>
            <SyncFacts device={device} timezone={timezone} />
            {device.counter_backfilled && (
              <p className="device-note">{device.sync_count_note}</p>
            )}
            <p className="device-note">
              Device ID: <code>{device.device_id}</code>
            </p>
          </section>
        )
      )}
      <section className="device-panel">
        <div className="device-panel-heading">
          <Layers size={20} />
          <h2>Sync history</h2>
          <span>{history.data?.total ?? 0} retained syncs</span>
        </div>
        <p className="device-note">
          Original records are kept for up to{" "}
          {detail.data?.retention?.raw_days || 90} days, subject to storage
          limits. Sync receipts are kept for up to{" "}
          {detail.data?.retention?.receipt_days || 365} days. First and last
          sync dates and the successful sync count continue beyond that history.
        </p>
        {history.loading ? (
          <DataLoading />
        ) : history.data?.batches?.length ? (
          <>
            <div className="device-history-list">
              {history.data.batches.map((batch) => (
                <button
                  className="device-history-row"
                  key={batch.batch_id}
                  onClick={() => setBatchId(batch.batch_id)}
                  aria-label={`View sync from ${formatTime(batch.received_at_ms, timezone)}`}
                >
                  <span className="device-icon">
                    <CheckCircle2 size={20} />
                  </span>
                  <span>
                    <b>{formatTime(batch.received_at_ms, timezone)}</b>
                    <small>
                      Collected {formatTime(batch.collected_at_ms, timezone)}
                    </small>
                    <small>
                      {formatTime(batch.window_start_ms, timezone)} –{" "}
                      {formatTime(batch.window_end_ms, timezone)}
                    </small>
                  </span>
                  <span className="device-history-meta">
                    <span
                      className={`device-badge ${batch.raw_retained ? "" : "muted"}`}
                    >
                      {batch.raw_retained
                        ? "Records available"
                        : "Receipt only"}
                    </span>
                    <small>
                      {batch.source_count != null
                        ? `${batch.source_count} sources · `
                        : ""}
                      {formatBytes(batch.payload_bytes)}
                    </small>
                  </span>
                  <ArrowRight size={18} />
                </button>
              ))}
            </div>
            <Pagination
              offset={offset}
              count={history.data.batches.length}
              total={history.data.total}
              hasMore={history.data.has_more}
              onPrevious={() => setOffset(Math.max(0, offset - 20))}
              onNext={() => setOffset(offset + 20)}
            />
          </>
        ) : (
          !history.error && (
            <DeviceEmpty title="Waiting for the first sync.">
              Tap Sync now on the connected phone. Its uploaded records will
              appear here.
            </DeviceEmpty>
          )
        )}
      </section>
      {remove && (
        <DeleteDevice
          device={device}
          onCancel={() => setRemove(false)}
          onDeleted={onRemoved}
        />
      )}
    </div>
  );
}

function Pagination({ offset, count, total, hasMore, onPrevious, onNext }) {
  return (
    <div className="device-pagination">
      <span>
        {total ? `${offset + 1}–${offset + count} of ${total}` : "No records"}
      </span>
      <button className="outline" disabled={offset === 0} onClick={onPrevious}>
        Previous
      </button>
      <button className="outline" disabled={!hasMore} onClick={onNext}>
        Next
      </button>
    </div>
  );
}

function BatchDetails({ deviceId, batchId, timezone, onBack }) {
  const [source, setSource] = useState(null);
  const [revision, setRevision] = useState(0);
  const [downloadError, setDownloadError] = useState("");
  const [downloading, setDownloading] = useState(false);
  const path = `/devices/${encodeURIComponent(deviceId)}/batches/${encodeURIComponent(batchId)}`;
  const state = useDeviceRequest(path, revision);
  const batch = state.data?.batch;
  async function download() {
    setDownloading(true);
    setDownloadError("");
    try {
      const payload = await api(`${path}/raw`);
      const url = URL.createObjectURL(
        new Blob([JSON.stringify(payload, null, 2)], {
          type: "application/json",
        }),
      );
      const link = document.createElement("a");
      link.href = url;
      link.download = `forma-sync-${batchId}.json`;
      link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      setDownloadError(error.message);
    } finally {
      setDownloading(false);
    }
  }
  return (
    <div className="device-page">
      <BackButton onClick={onBack}>Device history</BackButton>
      <div className="page-heading">
        <div className="eyebrow">COLLECTION DETAILS</div>
        <div className="heading-row">
          <div>
            <h1>Synced data</h1>
            <p>
              {batch
                ? formatTime(batch.received_at_ms, timezone)
                : "Loading this sync…"}
            </p>
          </div>
          <div className="device-actions">
            <RefreshButton
              loading={state.loading}
              onClick={() => setRevision((value) => value + 1)}
            />
            {state.data?.raw_retained && (
              <button
                className="outline"
                disabled={downloading}
                onClick={download}
              >
                <Download size={16} />
                {downloading ? "Preparing…" : "Download JSON"}
              </button>
            )}
          </div>
        </div>
      </div>
      <DataError
        error={state.error || downloadError}
        retry={() => setRevision((value) => value + 1)}
      />
      {state.loading ? (
        <DataLoading />
      ) : (
        batch && (
          <>
            <section className="device-panel">
              <div className="device-panel-heading">
                <CheckCircle2 size={20} />
                <h2>Server acknowledged</h2>
              </div>
              <dl className="device-facts">
                <div>
                  <dt>Collected</dt>
                  <dd>{formatTime(batch.collected_at_ms, timezone)}</dd>
                </div>
                <div>
                  <dt>Received</dt>
                  <dd>{formatTime(batch.received_at_ms, timezone)}</dd>
                </div>
                <div>
                  <dt>Export size</dt>
                  <dd>{formatBytes(batch.payload_bytes)}</dd>
                </div>
              </dl>
              <p className="device-note">
                Batch ID: <code>{batch.batch_id}</code>
              </p>
              <p className="device-note">
                History window: {formatTime(batch.window_start_ms, timezone)} –{" "}
                {formatTime(batch.window_end_ms, timezone)}.
              </p>
            </section>
            {!state.data.raw_retained ? (
              <DeviceEmpty title="The sync receipt is retained.">
                Original records for this sync are no longer available under the
                history retention limits. Its acknowledgement and sync dates
                remain above.
              </DeviceEmpty>
            ) : (
              <>
                <section className="device-panel">
                  <div className="device-panel-heading">
                    <CalendarDays size={20} />
                    <h2>Collected sources</h2>
                    <span>{state.data.sources?.length || 0} sources</span>
                  </div>
                  <p className="device-note">
                    Choose a source to inspect its original records,
                    permissions, and any unavailable-data explanation.
                  </p>
                  <div className="device-source-grid">
                    {(state.data.sources || []).map((item) => (
                      <button
                        key={item.key}
                        className={`device-source ${source?.key === item.key ? "selected" : ""}`}
                        onClick={() => setSource(item)}
                        aria-label={`View ${sourceLabel(item.key)} records`}
                      >
                        <b>{sourceLabel(item.key)}</b>
                        <span>
                          {item.record_count} records ·{" "}
                          {item.status?.replace(/_/g, " ") || "Unknown"}
                          {item.complete === false ? " · Incomplete" : ""}
                        </span>
                        <ArrowRight size={15} />
                      </button>
                    ))}
                  </div>
                </section>
                {source && (
                  <SourceRecords
                    key={`${source.key}/${revision}`}
                    path={`${path}/sources/${encodeURIComponent(source.key)}`}
                    source={source}
                  />
                )}
                <details className="device-panel">
                  <summary>Permissions at collection</summary>
                  <pre className="device-json">
                    {JSON.stringify(state.data.permissions, null, 2)}
                  </pre>
                </details>
              </>
            )}
          </>
        )
      )}
    </div>
  );
}

function SourceRecords({ path, source }) {
  const [offset, setOffset] = useState(0);
  const [revision, setRevision] = useState(0);
  const state = useDeviceRequest(`${path}?limit=25&offset=${offset}`, revision);
  const root = React.useRef(null);
  useEffect(() => {
    root.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, []);
  return (
    <section className="device-panel" ref={root}>
      <div className="device-panel-heading">
        <h2>{sourceLabel(source.key)}</h2>
        <RefreshButton
          loading={state.loading}
          onClick={() => setRevision((value) => value + 1)}
        />
      </div>
      <DataError error={state.error} />
      {state.loading ? (
        <DataLoading label="Loading source records…" />
      ) : (
        state.data && (
          <>
            <details className="device-source-metadata" open>
              <summary>Source metadata and availability</summary>
              <pre className="device-json">
                {JSON.stringify(state.data.source?.metadata || {}, null, 2)}
              </pre>
            </details>
            <h3>Raw records</h3>
            {state.data.records?.length ? (
              state.data.records.map((record, index) => (
                <details className="device-record" key={offset + index}>
                  <summary>Record {offset + index + 1}</summary>
                  <pre className="device-json">
                    {JSON.stringify(record, null, 2)}
                  </pre>
                </details>
              ))
            ) : (
              <p className="device-note">
                This source has no raw records in this sync. Review its metadata
                for permission, availability, or snapshot details.
              </p>
            )}
            <Pagination
              offset={offset}
              count={state.data.records?.length || 0}
              total={state.data.total}
              hasMore={state.data.has_more}
              onPrevious={() => setOffset(Math.max(0, offset - 25))}
              onNext={() => setOffset(offset + 25)}
            />
          </>
        )
      )}
    </section>
  );
}
