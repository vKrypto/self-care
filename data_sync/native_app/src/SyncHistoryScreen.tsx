import React, {useCallback, useEffect, useRef, useState} from 'react';
import {ActivityIndicator, Modal, Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import {dataSync} from './native';
import {readableBytes} from './collection';
import {collectionLabel, historyJobs, HistoryRequestGate, jsonEntries, mergeSections, scalarLabel, sourceLabel, timestampHint, uploadLabel} from './history';
import type {HistoryDetails, HistoryJob, HistoryRecordsPage, HistorySource} from './types';

const JOB_PAGE_SIZE = 20;
const RECORD_PAGE_SIZE = 25;

function Action({title, onPress, disabled = false}: {title: string; onPress: () => void; disabled?: boolean}) {
  return <Pressable accessibilityRole="button" accessibilityState={{disabled}} disabled={disabled} onPress={onPress}
    style={({pressed}) => [styles.action, (disabled || pressed) && styles.dim]}>
    <Text style={styles.actionText}>{title}</Text>
  </Pressable>;
}

function time(value: number | null): string { return value === null ? 'Not yet' : new Date(value).toLocaleString(); }

/** Each mounted view owns its requests, including retries and additional pages. */
function usePages<T extends {nextOffset: number | null}>(fetchPage: (offset: number) => Promise<T>) {
  const [pages, setPages] = useState<T[]>([]);
  const [nextOffset, setNextOffset] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const failedOffset = useRef(0);
  const busy = useRef(false);
  const gate = useRef(new HistoryRequestGate());

  const load = useCallback(async (offset: number) => {
    if (busy.current) { return; }
    busy.current = true;
    const ticket = gate.current.begin();
    setLoading(true); setError(''); failedOffset.current = offset;
    try {
      const page = await fetchPage(offset);
      if (!gate.current.isCurrent(ticket)) { return; }
      setPages(previous => offset === 0 ? [page] : [...previous, page]);
      setNextOffset(page.nextOffset);
    } catch (cause) {
      if (gate.current.isCurrent(ticket)) { setError(cause instanceof Error ? cause.message : 'History could not load. Please retry.'); }
    } finally {
      if (gate.current.isCurrent(ticket)) { busy.current = false; setLoading(false); }
    }
  }, [fetchPage]);

  useEffect(() => {
    busy.current = false;
    void load(0);
    return () => { gate.current.invalidate(); busy.current = false; };
  }, [load]);

  return {pages, nextOffset, loading, error, retry: () => { void load(failedOffset.current); },
    refresh: () => { void load(0); }, more: () => { if (nextOffset !== null) { void load(nextOffset); } }};
}

function LoadState({loading, error, retry}: {loading: boolean; error: string; retry: () => void}) {
  return <>
    {loading && <ActivityIndicator accessibilityLabel="Loading sync history" color="#267957" style={styles.spinner} />}
    {!!error && <View accessibilityRole="alert" style={styles.error}><Text style={styles.errorText}>{error}</Text>
      <Action title="Retry" onPress={retry} disabled={loading} /></View>}
  </>;
}

function JsonNode({name, value, depth = 0}: {name: string; value: unknown; depth?: number}) {
  const children = jsonEntries(value);
  const [expanded, setExpanded] = useState(depth === 0);
  const [shown, setShown] = useState(12);
  if (children === null) {
    const hint = timestampHint(name, value);
    return <View style={styles.field}>
      <Text selectable style={styles.raw}><Text style={styles.fieldName}>{name}: </Text>{scalarLabel(value)}</Text>
      {hint && <Text style={styles.hint}>{hint}</Text>}
    </View>;
  }
  return <View style={styles.field}>
    <Pressable accessibilityRole="button" accessibilityLabel={`${expanded ? 'Collapse' : 'Expand'} ${name}`}
      accessibilityState={{expanded}} onPress={() => setExpanded(!expanded)} style={styles.jsonToggle}>
      <Text style={styles.raw}>{expanded ? '▾' : '▸'} <Text style={styles.fieldName}>{name}</Text> {Array.isArray(value) ? `[${children.length} items]` : `{${children.length} fields}`}</Text>
    </Pressable>
    {expanded && <View style={styles.nested}>
      {children.slice(0, shown).map(([key, child]) => <JsonNode key={key} name={key} value={child} depth={depth + 1} />)}
      {children.length > shown && <Action title={`Show more fields (${children.length - shown} remaining)`} onPress={() => setShown(shown + 12)} />}
      {children.length === 0 && <Text style={styles.hint}>{Array.isArray(value) ? '[]' : '{}'}</Text>}
    </View>}
  </View>;
}

function JobSummary({job}: {job: HistoryJob}) {
  return <>
    <View style={styles.row}><Text style={styles.cardTitle}>{job.status === 'legacy' ? 'Legacy queued collection' : job.background ? 'Background collection' : 'Manual collection'}</Text>
      <Text style={styles.badge}>{collectionLabel(job.status)}</Text></View>
    <Text style={styles.body}>{time(job.startedAt)}</Text>
    <Text style={styles.hint}>{job.recordCount} records · {job.sourceCount} collected sources · {job.batchCount} batches</Text>
    <Text style={styles.upload}>{uploadLabel(job.uploadStatus)}</Text>
    <Text style={styles.hint}>{job.queuedBatches} queued · {job.syncedBatches} server acknowledged</Text>
    {!!job.error && <Text style={job.status === 'legacy' ? styles.hint : styles.errorText}>{job.error}</Text>}
  </>;
}

function HistoryList({onSelect}: {onSelect: (jobId: string) => void}) {
  const fetchPage = useCallback((offset: number) => dataSync.syncHistory(offset, JOB_PAGE_SIZE), []);
  const result = usePages(fetchPage);
  const jobs = historyJobs(result.pages);
  const latest = result.pages[result.pages.length - 1];
  const retention = latest?.retention;
  return <ScrollView contentContainerStyle={styles.content}>
    <Text style={styles.title}>Sync history</Text>
    <Text style={styles.body}>Open a collection job to see the sources read, saved records and server acknowledgements. This history is available without signing in.</Text>
    <View style={styles.row}><Text style={styles.hint}>{latest ? `${latest.total} collection jobs` : 'Loading collection jobs'}</Text>
      <Action title="Refresh history" onPress={result.refresh} disabled={result.loading} /></View>
    {retention && <Text style={styles.hint}>Synced records are kept locally for {retention.syncedDays} days, subject to a {readableBytes(retention.syncedBytes)} limit. Pending uploads remain until synced. Up to {retention.maxJobs} finished job summaries are kept, plus jobs with pending uploads.</Text>}
    {jobs.map(job => <Pressable key={job.id} accessibilityRole="button" accessibilityLabel={`Open ${job.background ? 'background' : 'manual'} collection from ${time(job.startedAt)}`}
      onPress={() => onSelect(job.id)} style={({pressed}) => [styles.card, pressed && styles.dim]}>
      <JobSummary job={job} />
      {!job.detailsAvailable && <Text style={styles.hint}>{job.batchCount === 0 ? 'No raw data batches saved.' : 'Only summary information is retained.'}</Text>}
      <Text style={styles.actionText}>View collection →</Text>
    </Pressable>)}
    {!result.loading && !result.error && jobs.length === 0 && <View style={styles.card}><Text style={styles.cardTitle}>No collection jobs yet</Text>
      <Text style={styles.body}>Use Collect now or wait for the next background collection. Earlier uploads made before history recording was added cannot be reconstructed.</Text></View>}
    <LoadState loading={result.loading} error={result.error} retry={result.retry} />
    {result.nextOffset !== null && <Action title="Load 20 older jobs" onPress={result.more} disabled={result.loading} />}
  </ScrollView>;
}

function JobDetails({jobId, onSelectSource}: {jobId: string; onSelectSource: (source: HistorySource) => void}) {
  const fetchDetails = useCallback(async () => ({details: await dataSync.collectionDetails(jobId), nextOffset: null}), [jobId]);
  const result = usePages(fetchDetails);
  const details: HistoryDetails | undefined = result.pages[0]?.details;
  return <ScrollView contentContainerStyle={styles.content}>
    <View style={styles.row}><Text style={styles.title}>Collection details</Text><Action title="Refresh" onPress={result.refresh} disabled={result.loading} /></View>
    {details && <>
      <View style={styles.card}>
        <JobSummary job={details} />
        <Text style={styles.hint}>Started: {time(details.startedAt)}{ '\n' }Finished: {details.finishedAt === null ? 'Still in progress' : time(details.finishedAt)}{ '\n' }Last server acknowledgement: {time(details.lastSyncedAt)}</Text>
        <Text selectable style={styles.hint}>Job ID: {details.id} · collection status: {details.status}</Text>
        {!details.detailsAvailable && <Text style={styles.body}>{details.batchCount === 0 ? 'No raw data batches were saved in this job. Source checks and collection errors are shown below.' : 'Raw payloads are no longer retained for this job. The source and upload summaries below remain available.'}</Text>}
      </View>
      <Text style={styles.sectionTitle}>Sources</Text>
      <Text style={styles.hint}>Denied or unavailable checks show what Android could not read; a source may have mixed access across batches.</Text>
      {details.sources.map(source => <Pressable key={source.name} accessibilityRole="button"
        accessibilityLabel={`View ${sourceLabel(source.name)} records and metadata`} onPress={() => onSelectSource(source)}
        style={({pressed}) => [styles.card, pressed && styles.dim]}>
        <View style={styles.row}><Text style={styles.cardTitle}>{sourceLabel(source.name)}</Text><Text style={styles.badge}>{source.status.replace(/_/g, ' ')}</Text></View>
        <Text selectable style={styles.hint}>{source.name}</Text>
        <Text style={styles.body}>{source.recordCount} records · {source.collected ? 'collected' : 'not collected'} · {source.complete ? 'complete' : 'incomplete'}</Text>
        {!!source.reason && <Text style={styles.hint}>{source.reason.replace(/_/g, ' ')}</Text>}
        <Text style={styles.actionText}>{source.detailsAvailable ? 'View raw records & metadata →' : 'View source summary →'}</Text>
      </Pressable>)}
      {details.sources.length === 0 && <Text style={styles.body}>No sources were recorded in this job.</Text>}
      <Text style={styles.sectionTitle}>Batches & upload receipts</Text>
      {details.batches.map(batch => <View key={batch.id} style={styles.card}>
        <View style={styles.row}><Text style={styles.cardTitle}>{batch.status === 'synced' ? 'Server acknowledged' : batch.status === 'failed' ? 'Upload failed' : 'Stored locally · queued'}</Text><Text style={styles.hint}>{readableBytes(batch.bytes)}</Text></View>
        <Text style={styles.body}>Window: {time(batch.windowStart)} → {time(batch.windowEnd)}</Text>
        <Text style={styles.hint}>Collected: {time(batch.collectedAt)}{ '\n' }Server acknowledged: {time(batch.syncedAt)}</Text>
        <Text selectable style={styles.hint}>Batch ID: {batch.id}{ '\n' }Server: {batch.serverUrl ?? 'Not connected'}{ '\n' }Account ID: {batch.userId ?? 'Unassigned'}</Text>
        {!!batch.error && <Text style={styles.errorText}>{batch.error}</Text>}
        {!batch.detailsAvailable && <Text style={styles.hint}>Raw data for this batch is no longer retained locally.</Text>}
      </View>)}
      {details.batches.length === 0 && <Text style={styles.body}>No data batches were saved in this collection job.</Text>}
      <Text style={styles.sectionTitle}>Latest permissions snapshot in this job</Text>
      <View style={styles.card}>{details.permissions ? <JsonNode name="permissions" value={details.permissions} /> : <Text style={styles.hint}>Permission details are unavailable for this job.</Text>}</View>
    </>}
    <LoadState loading={result.loading} error={result.error} retry={result.retry} />
  </ScrollView>;
}

function SourceRecords({jobId, source}: {jobId: string; source: HistorySource}) {
  const fetchPage = useCallback(async (offset: number) => {
    const page = await dataSync.collectionRecords(jobId, source.name, offset, RECORD_PAGE_SIZE);
    if (page.jobId !== jobId || page.source !== source.name) { throw new Error('The record page does not match this collection source. Please retry.'); }
    return page;
  }, [jobId, source.name]);
  const result = usePages<HistoryRecordsPage>(fetchPage);
  const latest = result.pages[result.pages.length - 1];
  const records = result.pages.flatMap(page => page.records);
  const sections = result.pages.reduce((all, page) => mergeSections(all, page.sections), [] as HistoryRecordsPage['sections']);
  return <ScrollView contentContainerStyle={styles.content}>
    <Text style={styles.title}>{sourceLabel(source.name)}</Text><Text selectable style={styles.hint}>{source.name}</Text>
    <View style={styles.card}><Text style={styles.cardTitle}>{source.status.replace(/_/g, ' ')} · {source.collected ? 'collected' : 'not collected'}</Text>
      <Text style={styles.body}>{source.recordCount} records · {source.complete ? 'complete' : 'incomplete'}</Text>
      {!!source.reason && <Text selectable style={styles.hint}>{source.reason}</Text>}
    </View>
    <View style={styles.row}><Text style={styles.hint}>{latest ? `${latest.total} collected · ${latest.available} retained · ${records.length} shown` : 'Loading records'}</Text>
      <Action title="Refresh source" onPress={result.refresh} disabled={result.loading} /></View>
    {latest && !latest.detailsAvailable && <View style={styles.card}><Text style={styles.cardTitle}>Raw details unavailable</Text>
      <Text style={styles.body}>{source.collected ? 'Raw payloads for this source are no longer retained locally. Its collection summary remains available.' : 'This source was not collected in this job. Its permission or availability summary is shown above.'}</Text></View>}
    {latest && latest.available < latest.total && <Text style={styles.hint}>{latest.total - latest.available} previously collected records are no longer retained locally.</Text>}
    <Text style={styles.sectionTitle}>Section metadata</Text>
    <Text style={styles.hint}>These are the stored fields returned by Android, including availability, history limits and Health Connect status details. Expand nested fields to inspect their original values.</Text>
    {sections.map(section => <View key={section.batchId} style={styles.card}>
      <Text selectable style={styles.hint}>Batch: {section.batchId}</Text><JsonNode name="metadata" value={section.metadata} />
    </View>)}
    {latest?.detailsAvailable && sections.length === 0 && <Text style={styles.hint}>No section metadata is retained.</Text>}
    <Text style={styles.sectionTitle}>Raw records</Text>
    {records.map((record, index) => <View key={`${record.batchId}-${index}`} style={styles.card}>
      <Text style={styles.cardTitle}>Record {index + 1}</Text><Text selectable style={styles.hint}>Batch: {record.batchId}</Text>
      <JsonNode name="record" value={record.value} />
    </View>)}
    {latest?.detailsAvailable && latest.available === 0 && <Text style={styles.body}>This retained source section has no raw records. Metadata above may contain a device snapshot or an explanation.</Text>}
    <LoadState loading={result.loading} error={result.error} retry={result.retry} />
    {result.nextOffset !== null && <Action title="Load 25 more records" onPress={result.more} disabled={result.loading} />}
  </ScrollView>;
}

export default function SyncHistoryScreen({onClose}: {onClose: () => void}) {
  const [jobId, setJobId] = useState<string | null>(null);
  const [source, setSource] = useState<HistorySource | null>(null);
  const back = () => {
    if (source !== null) { setSource(null); }
    else if (jobId !== null) { setJobId(null); }
    else { onClose(); }
  };
  return <Modal visible animationType="slide" presentationStyle="fullScreen" onRequestClose={back}>
    <SafeAreaView style={styles.screen}>
      <View style={styles.header}><Action title={jobId === null ? 'Dashboard' : source === null ? 'All jobs' : 'Collection job'} onPress={back} />
        <Text style={styles.headerTitle}>Sync history</Text><Action title="Close" onPress={onClose} /></View>
      {jobId === null ? <HistoryList onSelect={setJobId} /> : source === null ?
        <JobDetails key={jobId} jobId={jobId} onSelectSource={setSource} /> :
        <SourceRecords key={`${jobId}-${source.name}`} jobId={jobId} source={source} />}
    </SafeAreaView>
  </Modal>;
}

const styles = StyleSheet.create({
  screen: {flex: 1, backgroundColor: '#f5f7f2'},
  header: {padding: 14, borderBottomWidth: 1, borderColor: '#dfe6d8', flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8},
  headerTitle: {fontSize: 15, fontWeight: '700', color: '#213c2b'},
  content: {padding: 20, gap: 14, paddingBottom: 40},
  title: {fontSize: 28, fontWeight: '700', color: '#213c2b'},
  sectionTitle: {fontSize: 18, fontWeight: '700', color: '#213c2b', marginTop: 6},
  card: {padding: 16, borderRadius: 14, borderWidth: 1, borderColor: '#dfe6d8', backgroundColor: '#fff', gap: 8},
  cardTitle: {fontSize: 15, fontWeight: '700', color: '#213c2b', flexShrink: 1},
  body: {fontSize: 14, color: '#556452', lineHeight: 21}, hint: {fontSize: 12, color: '#768171', lineHeight: 18},
  row: {flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: 8},
  badge: {fontSize: 11, color: '#556452'}, upload: {fontSize: 13, color: '#267957', fontWeight: '600'},
  action: {paddingHorizontal: 12, paddingVertical: 10, borderRadius: 9, backgroundColor: '#eaf0e4', alignItems: 'center'},
  actionText: {fontSize: 13, fontWeight: '700', color: '#305a37'}, dim: {opacity: 0.5},
  error: {padding: 14, gap: 10, backgroundColor: '#fce8df', borderRadius: 10}, errorText: {fontSize: 13, color: '#9c412f', lineHeight: 19},
  spinner: {padding: 12}, raw: {fontSize: 12, lineHeight: 19, color: '#344c39', fontFamily: 'monospace'},
  field: {gap: 3, marginVertical: 2}, fieldName: {fontWeight: '700'}, jsonToggle: {paddingVertical: 5},
  nested: {paddingLeft: 12, borderLeftWidth: 1, borderColor: '#e0e6da', gap: 3},
});
