import React from 'react';
import {
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import type {DeviceGuardCapabilities, LaunchableApp} from '@eye-saver/device-guard';
import type {
  FaceDistanceCalibration,
  FaceDistanceCapabilities,
  FaceDistanceSnapshot,
} from '@eye-saver/face-distance-monitor';
import type {ProximityCapabilities} from '../../src';

export type DashboardTab = 'home' | 'setup' | 'apps' | 'distance';

type Props = {
  tab: DashboardTab;
  onTab: (tab: DashboardTab) => void;
  onLock: () => void;
  message: string;
  onDismissMessage: () => void;
  busy: boolean;
  setupReadyCount: number;
  durationMinutes: number;
  onDuration: (value: number) => void;
  onStart: () => void;
  guard: DeviceGuardCapabilities;
  faceEnrolled: boolean;
  onOverlayPermission: () => void | Promise<unknown>;
  onUsagePermission: () => void | Promise<unknown>;
  onDeviceAdmin: () => void | Promise<unknown>;
  onBatteryExemption: () => void | Promise<unknown>;
  onAutoStart: () => void | Promise<unknown>;
  onAutoStartConfirmed: (confirmed: boolean) => void | Promise<unknown>;
  onTamperGuard: (value: boolean) => void | Promise<unknown>;
  launchableApps: LaunchableApp[];
  restrictedPackages: string[];
  appQuery: string;
  onAppQuery: (value: string) => void;
  onToggleApp: (packageName: string) => void | Promise<unknown>;
  proximity: ProximityCapabilities;
  proximityEnabled: boolean;
  onProximityEnabled: (value: boolean) => void;
  distanceCapabilities: FaceDistanceCapabilities;
  distanceCalibration: FaceDistanceCalibration;
  distanceState: FaceDistanceSnapshot;
  distanceEnabled: boolean;
  onDistanceEnabled: (value: boolean) => void;
  distanceThresholdCm: number;
  onDistanceThreshold: (value: number) => void;
  distanceIntervalMs: number;
  onDistanceInterval: (value: number) => void;
  onCameraPermission: () => void | Promise<unknown>;
  onCalibrate: () => void | Promise<unknown>;
};

const COLORS = {
  ink: '#041813',
  panel: '#0b2821',
  panelSoft: '#10342b',
  line: '#24473d',
  mint: '#8ce5c2',
  mintStrong: '#bdf5df',
  champagne: '#e8c77e',
  text: '#f3fbf7',
  textSoft: '#a8bbb4',
  textMuted: '#768b83',
  amber: '#d8a94d',
};

const clock = (timestamp: number) => timestamp > 0
  ? new Date(timestamp).toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'})
  : '—';

export default function ParentDashboard(props: Props) {
  const filteredApps = props.launchableApps.filter(app => {
    const query = props.appQuery.trim().toLocaleLowerCase();
    return query.length === 0
      || app.label.toLocaleLowerCase().includes(query)
      || app.packageName.toLocaleLowerCase().includes(query);
  });
  const setupComplete = props.setupReadyCount === 4;

  return <SafeAreaView style={styles.page}>
    <StatusBar barStyle="light-content" backgroundColor={COLORS.ink} />
    <View style={styles.header}>
      <View style={styles.brandRow}>
        <View style={styles.brandMark}><Text style={styles.brandMarkText}>◉</Text></View>
        <View><Text style={styles.brandName}>Eye Saver</Text><Text style={styles.brandCaption}>Parent controls</Text></View>
      </View>
      <Pressable accessibilityRole="button" accessibilityLabel="Lock parent dashboard" onPress={props.onLock} style={styles.lockButton}><Text style={styles.lockButtonText}>Lock</Text></Pressable>
    </View>

    {!!props.message && <Pressable accessibilityRole="button" accessibilityLabel="Dismiss message" onPress={props.onDismissMessage} style={styles.messageBanner}><Text style={styles.messageText}>{props.message}</Text><Text style={styles.messageClose}>×</Text></Pressable>}

    <ScrollView contentContainerStyle={styles.scroll} keyboardShouldPersistTaps="handled">
      {props.tab === 'home' && <HomeTab {...props} setupComplete={setupComplete} />}
      {props.tab === 'setup' && <SetupTab {...props} />}
      {props.tab === 'apps' && <AppsTab {...props} filteredApps={filteredApps} />}
      {props.tab === 'distance' && <DistanceTab {...props} />}
    </ScrollView>

    <View style={styles.bottomNav}>
      <NavItem label="Home" symbol="⌂" active={props.tab === 'home'} onPress={() => props.onTab('home')} />
      <NavItem label="Setup" symbol="✓" badge={!setupComplete ? 4 - props.setupReadyCount : undefined} active={props.tab === 'setup'} onPress={() => props.onTab('setup')} />
      <NavItem label="Apps" symbol="▦" active={props.tab === 'apps'} onPress={() => props.onTab('apps')} />
      <NavItem label="Distance" symbol="◎" active={props.tab === 'distance'} onPress={() => props.onTab('distance')} />
    </View>
  </SafeAreaView>;
}

function HomeTab(props: Props & {setupComplete: boolean}) {
  return <>
    <View style={styles.heroCard}>
      <View style={styles.heroGlow} />
      <View style={styles.heroStatusRow}><View style={[styles.statusDot, props.setupComplete ? styles.statusDotReady : styles.statusDotPending]} /><Text style={styles.heroStatus}>{props.setupComplete ? 'READY TO PROTECT' : `SETUP ${props.setupReadyCount} OF 4`}</Text></View>
      <Text style={styles.heroTitle}>Healthy screen time,{`\n`}without the struggle.</Text>
      <Text style={styles.heroBody}>Set the time, choose protected apps and hand over the phone. Eye Saver handles the rest in the background.</Text>
      {!props.setupComplete && <SecondaryButton label={`Finish setup · ${4 - props.setupReadyCount} left`} onPress={() => props.onTab('setup')} />}
    </View>

    <Text style={styles.sectionLabel}>SESSION LENGTH</Text>
    <View style={styles.durationGrid}>{[1, 20, 30, 45].map(value => <Pressable accessibilityRole="button" key={value} onPress={() => props.onDuration(value)} style={[styles.durationChoice, props.durationMinutes === value && styles.durationChoiceActive]}><Text style={[styles.durationValue, props.durationMinutes === value && styles.durationValueActive]}>{value}</Text><Text style={[styles.durationUnit, props.durationMinutes === value && styles.durationUnitActive]}>{value === 1 ? 'MIN TEST' : 'MINUTES'}</Text></Pressable>)}</View>

    <Card title="Your protection plan" subtitle="Review before handing over the phone">
      <SummaryRow icon="◷" label="Screen time" value={`${props.durationMinutes} min`} />
      <SummaryRow icon="▣" label="Apps blocked at zero" value={`${props.restrictedPackages.length} selected`} onPress={() => props.onTab('apps')} />
      <SummaryRow icon="◎" label="Distance warning" value={props.distanceEnabled ? `${props.distanceThresholdCm} cm · On` : 'Off'} onPress={() => props.onTab('distance')} />
      <SummaryRow icon="◇" label="Tamper protection" value={props.guard.tamperGuardEnabled ? 'On' : 'Off'} onPress={() => props.onTab('setup')} />
    </Card>

    <PrimaryButton label={props.setupReadyCount < 2 ? 'Complete setup to start' : `Start ${props.durationMinutes}-minute child mode`} onPress={props.setupReadyCount < 2 ? () => props.onTab('setup') : props.onStart} disabled={props.busy} />
    <Text style={styles.centerNote}>A visible Android notification confirms when protection is active.</Text>
  </>;
}

function SetupTab(props: Props) {
  return <>
    <PageIntro title="Quick phone setup" subtitle="Tap each step. We open the exact Android page—enable Eye Saver, then press Back." />
    <View style={styles.progressTrack}><View style={[styles.progressFill, {width: `${props.setupReadyCount * 25}%`}]} /></View>
    <Text style={styles.progressText}>{props.setupReadyCount === 4 ? 'Required setup complete' : `${props.setupReadyCount}/4 required settings ready`}</Text>

    <Card title="Required permissions" subtitle="One-time setup on this phone">
      <StatusRow label="Display over other apps" ready={props.guard.canDrawOverlays} action="Allow" note="Shows the break screen above protected apps" onPress={props.onOverlayPermission} />
      <StatusRow label="Usage access" ready={props.guard.usageAccessGranted} action="Allow" note="Detects only which app is currently open" onPress={props.onUsagePermission} />
      <StatusRow label="Device Admin" ready={props.guard.isAdminActive} action="Activate" note="Adds protection against casual removal" onPress={props.onDeviceAdmin} />
      <StatusRow label="Battery: no restrictions" ready={props.guard.batteryOptimizationExempt} action="Allow" note="Keeps an active child session reliable" onPress={props.onBatteryExemption} />
      <ActionRow label={`${props.guard.deviceManufacturer} AutoStart`} action={props.guard.autoStartConfirmed ? 'Change' : 'Open'} note={props.guard.autoStartConfirmed ? 'Marked ready on this phone' : (props.guard.autoStartSettingsAvailable ? 'Recommended: enable Eye Saver on the next page' : 'Optional: app battery details will open')} onPress={props.onAutoStart} />
      <View style={styles.switchRow}><View style={styles.flex}><Text style={styles.rowTitle}>I enabled AutoStart</Text><Text style={styles.rowNote}>Android does not expose this switch status, so Eye Saver remembers your confirmation.</Text></View><Switch value={props.guard.autoStartConfirmed} onValueChange={value => { props.onAutoStartConfirmed(value); }} trackColor={{false: '#30443d', true: '#377f67'}} thumbColor={props.guard.autoStartConfirmed ? COLORS.mintStrong : '#9aaba5'} /></View>
    </Card>

    <Card title="Tamper protection" subtitle="Keep parent controls out of reach">
      <View style={styles.switchRow}><View style={styles.flex}><Text style={styles.rowTitle}>Protect Settings and installs</Text><Text style={styles.rowNote}>During child mode, blocks Settings, app info, package installers and app stores.</Text></View><Switch value={props.guard.tamperGuardEnabled} onValueChange={value => { props.onTamperGuard(value); }} trackColor={{false: '#30443d', true: '#377f67'}} thumbColor={props.guard.tamperGuardEnabled ? COLORS.mintStrong : '#9aaba5'} /></View>
      <View style={styles.infoBox}><Text style={styles.infoTitle}>Android limitation</Text><Text style={styles.infoText}>On a personal phone, protection is best effort. Only Device Owner setup on a dedicated child phone can prevent system-level Force Stop.</Text></View>
    </Card>

    <Card title="Protection health" subtitle="What continues in the background">
      <StatusRow label="Swipe-close recovery" ready note="Active protection continues after Recents is cleared" />
      <StatusRow label="Reboot recovery" ready note="Restores a saved session after a normal reboot" />
      <StatusRow label="Dedicated Device Owner" ready={props.guard.isDeviceOwner} note="Optional strongest protection for a child-only phone" />
      <StatusRow label="Child face profile" ready={props.faceEnrolled} note={props.faceEnrolled ? 'Face profile exists on this device' : 'Face identity verification is not enabled yet'} />
    </Card>

    <Card title="Privacy, clearly explained" subtitle="No account, ads or cloud upload">
      <PrivacyPoint title="Camera" text="Optional distance frames are analysed on this phone, never saved, and never sent anywhere." />
      <PrivacyPoint title="Usage access" text="Used to identify the currently open app while protection is active. Eye Saver does not build or upload browsing history." />
      <PrivacyPoint title="Background service" text="Android shows an ongoing notification whenever child protection or camera distance monitoring is running." />
    </Card>
  </>;
}

function AppsTab(props: Props & {filteredApps: LaunchableApp[]}) {
  return <>
    <PageIntro title="Choose protected apps" subtitle="Selected apps work normally during the timer and are covered by the break screen when time reaches zero." />
    <View style={styles.selectionSummary}><Text style={styles.selectionCount}>{props.restrictedPackages.length}</Text><View style={styles.flex}><Text style={styles.selectionTitle}>apps selected</Text><Text style={styles.selectionNote}>Keep Phone unselected so calls remain available</Text></View></View>
    <TextInput value={props.appQuery} onChangeText={props.onAppQuery} style={styles.searchInput} placeholder="Search apps" placeholderTextColor="#70857d" autoCorrect={false} />
    <Card title={props.filteredApps.length === props.launchableApps.length ? 'Apps on this phone' : `${props.filteredApps.length} matching apps`}>
      {props.filteredApps.map(app => <View key={app.packageName} style={styles.appRow}>
        <View style={styles.appInitial}><Text style={styles.appInitialText}>{app.label.trim().charAt(0).toUpperCase() || 'A'}</Text></View>
        <View style={styles.flex}><Text style={styles.rowTitle}>{app.label}</Text><Text style={styles.packageName} numberOfLines={1}>{app.packageName}</Text></View>
        <Switch value={props.restrictedPackages.includes(app.packageName)} onValueChange={() => { props.onToggleApp(app.packageName); }} trackColor={{false: '#30443d', true: '#377f67'}} thumbColor={props.restrictedPackages.includes(app.packageName) ? COLORS.mintStrong : '#9aaba5'} />
      </View>)}
      {props.filteredApps.length === 0 && <Text style={styles.emptyText}>No apps match “{props.appQuery}”.</Text>}
    </Card>
  </>;
}

function DistanceTab(props: Props) {
  return <>
    <PageIntro title="Viewing distance" subtitle="Get a gentle full-screen warning when the phone stays too close to a face." />
    <View style={styles.privacyHero}><Text style={styles.privacyHeroIcon}>◎</Text><View style={styles.flex}><Text style={styles.privacyHeroTitle}>Private by design</Text><Text style={styles.privacyHeroText}>Analysis stays on-device. No photo or video is saved.</Text></View></View>

    <Card title="Low-power proximity sensor" subtitle="Useful for near/far detection">
      <View style={styles.switchRow}><View style={styles.flex}><Text style={styles.rowTitle}>{props.proximity.available ? props.proximity.name ?? 'Proximity sensor' : 'Not available on this phone'}</Text><Text style={styles.rowNote}>{props.proximity.isLikelyBinary ? 'This sensor reports near/far—not reliable centimetres' : 'Runs only while Eye Saver is visible'}</Text></View><Switch value={props.proximityEnabled} onValueChange={props.onProximityEnabled} disabled={!props.proximity.available} trackColor={{false: '#30443d', true: '#377f67'}} thumbColor={props.proximityEnabled ? COLORS.mintStrong : '#9aaba5'} /></View>
    </Card>

    <Card title="Camera distance warning" subtitle="Optional · approximate measurement">
      <StatusRow label="Front camera access" ready={props.distanceCapabilities.cameraPermissionGranted} action="Allow" note="Android always shows the camera privacy indicator" onPress={props.onCameraPermission} />
      <View style={styles.infoBox}><Text style={styles.infoTitle}>Parent sets this up—not the child</Text><Text style={styles.infoText}>Hold the phone about 1 metre from the child. The live camera shows an oval and automatically captures four stable readings when the whole face is aligned.</Text></View>
      <StatusRow label="Child distance profile" ready={props.distanceCalibration.calibrated} action={props.distanceState.mode === 'calibrating' ? 'Running' : 'Open camera'} note={props.distanceCalibration.calibrated ? `Saved at ${clock(props.distanceCalibration.calibratedAtMs)}` : 'Live face guide · automatic capture · no photo saved'} onPress={props.distanceState.mode === 'calibrating' || props.distanceCalibration.calibrated ? undefined : props.onCalibrate} />
      {props.distanceCalibration.calibrated && <ActionRow label="Guided camera setup" action="Open camera" note="Redo now, or repeat later for a different child" onPress={props.onCalibrate} />}
      {props.distanceState.eventType === 'camera_error' && props.distanceState.mode !== 'calibrating' && <View style={styles.calibrationError}><Text style={styles.calibrationErrorTitle}>Calibration did not finish</Text><Text style={styles.calibrationErrorText}>{props.distanceState.message ?? 'Keep one face inside the guide and try again.'}</Text><SecondaryButton label="Try again" onPress={() => { props.onCalibrate(); }} /></View>}
      <View style={styles.switchRow}><View style={styles.flex}><Text style={styles.rowTitle}>Use during child mode</Text><Text style={styles.rowNote}>Checks at your selected interval to reduce battery use</Text></View><Switch value={props.distanceEnabled} onValueChange={props.onDistanceEnabled} disabled={!props.distanceCalibration.calibrated} trackColor={{false: '#30443d', true: '#377f67'}} thumbColor={props.distanceEnabled ? COLORS.mintStrong : '#9aaba5'} /></View>
      <Text style={styles.optionLabel}>Warn below</Text>
      <View style={styles.chips}>{[20, 25, 30].map(value => <Pressable key={value} onPress={() => props.onDistanceThreshold(value)} style={[styles.chip, props.distanceThresholdCm === value && styles.chipActive]}><Text style={[styles.chipText, props.distanceThresholdCm === value && styles.chipTextActive]}>{value} cm</Text></Pressable>)}</View>
      <Text style={styles.optionLabel}>Check every</Text>
      <View style={styles.chips}>{[1000, 1500, 3000].map(value => <Pressable key={value} onPress={() => props.onDistanceInterval(value)} style={[styles.chip, props.distanceIntervalMs === value && styles.chipActive]}><Text style={[styles.chipText, props.distanceIntervalMs === value && styles.chipTextActive]}>{value / 1000}s</Text></Pressable>)}</View>
      <Text style={styles.footnote}>A warning needs three consecutive close readings and clears 5 cm beyond the threshold. Face angle, camera crop and different faces can affect the estimate.</Text>
    </Card>
  </>;
}

function PageIntro({title, subtitle}: {title: string; subtitle: string}) { return <View style={styles.pageIntro}><Text style={styles.pageTitle}>{title}</Text><Text style={styles.pageSubtitle}>{subtitle}</Text></View>; }
function Card({title, subtitle, children}: React.PropsWithChildren<{title: string; subtitle?: string}>) { return <View style={styles.card}><Text style={styles.cardTitle}>{title}</Text>{subtitle && <Text style={styles.cardSubtitle}>{subtitle}</Text>}{children}</View>; }
function PrimaryButton({label, onPress, disabled}: {label: string; onPress: () => void; disabled?: boolean}) { return <Pressable accessibilityRole="button" onPress={onPress} disabled={disabled} style={({pressed}) => [styles.primary, (pressed || disabled) && styles.buttonDim]}><Text style={styles.primaryText}>{label}</Text></Pressable>; }
function SecondaryButton({label, onPress}: {label: string; onPress: () => void}) { return <Pressable accessibilityRole="button" onPress={onPress} style={({pressed}) => [styles.secondary, pressed && styles.buttonDim]}><Text style={styles.secondaryText}>{label}</Text></Pressable>; }
function StatusRow({label, ready, action, note, onPress}: {label: string; ready: boolean; action?: string; note?: string; onPress?: () => void | Promise<unknown>}) { return <View style={styles.statusRow}><View style={styles.flex}><Text style={styles.rowTitle}>{label}</Text>{note && <Text style={styles.rowNote}>{note}</Text>}</View><Pressable accessibilityRole={onPress ? 'button' : undefined} disabled={!onPress || ready} onPress={() => { onPress?.(); }} style={[styles.badge, ready ? styles.badgeReady : styles.badgePending]}><Text style={[styles.badgeText, ready && styles.badgeTextReady]}>{ready ? 'Ready' : action ?? 'Optional'}</Text></Pressable></View>; }
function ActionRow({label, action, note, onPress}: {label: string; action: string; note?: string; onPress: () => void | Promise<unknown>}) { return <View style={styles.statusRow}><View style={styles.flex}><Text style={styles.rowTitle}>{label}</Text>{note && <Text style={styles.rowNote}>{note}</Text>}</View><Pressable accessibilityRole="button" onPress={() => { onPress(); }} style={[styles.badge, styles.badgePending]}><Text style={styles.badgeText}>{action}</Text></Pressable></View>; }
function SummaryRow({icon, label, value, onPress}: {icon: string; label: string; value: string; onPress?: () => void}) { return <Pressable accessibilityRole={onPress ? 'button' : undefined} disabled={!onPress} onPress={onPress} style={styles.summaryRow}><View style={styles.summaryIcon}><Text style={styles.summaryIconText}>{icon}</Text></View><Text style={styles.summaryLabel}>{label}</Text><Text style={styles.summaryValue}>{value}</Text>{onPress && <Text style={styles.chevron}>›</Text>}</Pressable>; }
function PrivacyPoint({title, text}: {title: string; text: string}) { return <View style={styles.privacyPoint}><View style={styles.privacyCheck}><Text style={styles.privacyCheckText}>✓</Text></View><View style={styles.flex}><Text style={styles.rowTitle}>{title}</Text><Text style={styles.rowNote}>{text}</Text></View></View>; }
function NavItem({label, symbol, active, badge, onPress}: {label: string; symbol: string; active: boolean; badge?: number; onPress: () => void}) { return <Pressable accessibilityRole="tab" accessibilityState={{selected: active}} onPress={onPress} style={styles.navItem}><View><Text style={[styles.navSymbol, active && styles.navSymbolActive]}>{symbol}</Text>{badge != null && <View style={styles.navBadge}><Text style={styles.navBadgeText}>{badge}</Text></View>}</View><Text style={[styles.navLabel, active && styles.navLabelActive]}>{label}</Text></Pressable>; }

const styles = StyleSheet.create({
  page: {flex: 1, backgroundColor: COLORS.ink},
  header: {minHeight: 72, paddingHorizontal: 20, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderBottomColor: '#14352c', borderBottomWidth: StyleSheet.hairlineWidth},
  brandRow: {flexDirection: 'row', alignItems: 'center', gap: 11},
  brandMark: {width: 38, height: 38, borderRadius: 13, alignItems: 'center', justifyContent: 'center', backgroundColor: '#174638', borderColor: '#477560', borderWidth: 1},
  brandMarkText: {color: COLORS.champagne, fontSize: 23, fontWeight: '800'},
  brandName: {color: COLORS.text, fontSize: 18, fontWeight: '800', letterSpacing: -0.3},
  brandCaption: {color: COLORS.textMuted, fontSize: 11, marginTop: 1},
  lockButton: {paddingHorizontal: 15, paddingVertical: 9, borderRadius: 99, backgroundColor: '#12342c'},
  lockButtonText: {color: COLORS.mint, fontSize: 13, fontWeight: '800'},
  messageBanner: {marginHorizontal: 16, marginTop: 8, paddingHorizontal: 14, paddingVertical: 11, borderRadius: 13, backgroundColor: '#4b3715', flexDirection: 'row', alignItems: 'center', gap: 10},
  messageText: {color: '#ffe8b0', flex: 1, fontSize: 12, lineHeight: 17},
  messageClose: {color: '#ffe8b0', fontSize: 22, lineHeight: 22},
  scroll: {padding: 18, paddingBottom: 32},
  heroCard: {overflow: 'hidden', minHeight: 278, padding: 23, borderRadius: 28, backgroundColor: '#0d392e', borderWidth: 1, borderColor: '#336452', marginBottom: 26},
  heroGlow: {position: 'absolute', width: 220, height: 220, borderRadius: 110, backgroundColor: '#1d6952', opacity: 0.42, right: -80, top: -70},
  heroStatusRow: {flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 28},
  statusDot: {width: 8, height: 8, borderRadius: 4}, statusDotReady: {backgroundColor: COLORS.mint}, statusDotPending: {backgroundColor: COLORS.champagne},
  heroStatus: {color: COLORS.champagne, fontSize: 11, fontWeight: '900', letterSpacing: 1.5},
  heroTitle: {color: COLORS.text, fontSize: 31, lineHeight: 37, fontWeight: '800', letterSpacing: -1},
  heroBody: {color: '#b7cbc3', fontSize: 14, lineHeight: 21, marginTop: 12, maxWidth: 310},
  sectionLabel: {color: COLORS.textMuted, fontSize: 11, fontWeight: '900', letterSpacing: 1.5, marginBottom: 10, marginLeft: 3},
  durationGrid: {flexDirection: 'row', gap: 8, marginBottom: 20},
  durationChoice: {flex: 1, minHeight: 76, borderRadius: 18, backgroundColor: COLORS.panel, borderWidth: 1, borderColor: COLORS.line, alignItems: 'center', justifyContent: 'center'},
  durationChoiceActive: {backgroundColor: COLORS.mint, borderColor: COLORS.mint},
  durationValue: {color: COLORS.text, fontSize: 23, fontWeight: '800'}, durationValueActive: {color: COLORS.ink},
  durationUnit: {color: COLORS.textMuted, fontSize: 8, fontWeight: '900', marginTop: 3}, durationUnitActive: {color: '#245446'},
  card: {backgroundColor: COLORS.panel, borderColor: COLORS.line, borderWidth: 1, borderRadius: 22, padding: 17, marginBottom: 15},
  cardTitle: {color: COLORS.text, fontSize: 17, fontWeight: '800'},
  cardSubtitle: {color: COLORS.textMuted, fontSize: 12, marginTop: 3, marginBottom: 10},
  primary: {backgroundColor: COLORS.mint, minHeight: 56, borderRadius: 17, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 18, marginTop: 5},
  primaryText: {color: COLORS.ink, fontSize: 16, fontWeight: '900'},
  secondary: {alignSelf: 'flex-start', paddingHorizontal: 15, paddingVertical: 11, borderRadius: 13, backgroundColor: '#f0d392', marginTop: 18},
  secondaryText: {color: '#14241f', fontSize: 13, fontWeight: '900'}, buttonDim: {opacity: 0.48},
  centerNote: {color: COLORS.textMuted, fontSize: 11, lineHeight: 16, textAlign: 'center', marginTop: 10},
  summaryRow: {minHeight: 50, flexDirection: 'row', alignItems: 'center', borderTopColor: '#193d33', borderTopWidth: StyleSheet.hairlineWidth},
  summaryIcon: {width: 29, height: 29, borderRadius: 10, alignItems: 'center', justifyContent: 'center', backgroundColor: '#173c32', marginRight: 10},
  summaryIconText: {color: COLORS.mint, fontSize: 15, fontWeight: '800'}, summaryLabel: {color: COLORS.textSoft, flex: 1, fontSize: 13}, summaryValue: {color: COLORS.text, fontSize: 13, fontWeight: '800'}, chevron: {color: COLORS.textMuted, fontSize: 21, marginLeft: 7},
  pageIntro: {marginBottom: 18}, pageTitle: {color: COLORS.text, fontSize: 28, fontWeight: '800', letterSpacing: -0.7}, pageSubtitle: {color: COLORS.textSoft, fontSize: 14, lineHeight: 21, marginTop: 7},
  progressTrack: {height: 7, borderRadius: 99, backgroundColor: '#17372f', overflow: 'hidden'}, progressFill: {height: 7, borderRadius: 99, backgroundColor: COLORS.mint}, progressText: {color: COLORS.mint, fontSize: 12, fontWeight: '800', marginTop: 8, marginBottom: 17},
  statusRow: {flexDirection: 'row', alignItems: 'center', gap: 12, paddingVertical: 13, borderTopColor: '#193d33', borderTopWidth: StyleSheet.hairlineWidth},
  flex: {flex: 1}, rowTitle: {color: COLORS.text, fontSize: 14, fontWeight: '700'}, rowNote: {color: COLORS.textMuted, fontSize: 11, lineHeight: 16, marginTop: 3},
  badge: {paddingHorizontal: 11, minHeight: 32, borderRadius: 99, alignItems: 'center', justifyContent: 'center', maxWidth: 88}, badgeReady: {backgroundColor: '#174b3c'}, badgePending: {backgroundColor: '#624919'}, badgeText: {color: '#ffe4a5', fontSize: 11, fontWeight: '900'}, badgeTextReady: {color: COLORS.mintStrong},
  switchRow: {flexDirection: 'row', alignItems: 'center', gap: 12, paddingVertical: 11},
  infoBox: {backgroundColor: '#17332c', borderRadius: 14, padding: 13, marginTop: 10}, infoTitle: {color: COLORS.champagne, fontSize: 12, fontWeight: '800'}, infoText: {color: COLORS.textSoft, fontSize: 11, lineHeight: 17, marginTop: 4},
  calibrationGuide: {alignItems: 'center', backgroundColor: '#09251e', borderColor: '#2b5b4b', borderWidth: 1, borderRadius: 20, padding: 18, marginVertical: 12},
  calibrationEyebrow: {color: COLORS.champagne, fontSize: 10, fontWeight: '900', letterSpacing: 1.2},
  faceStage: {width: 150, height: 150, alignItems: 'center', justifyContent: 'center', marginVertical: 14},
  facePulse: {position: 'absolute', width: 144, height: 144, borderRadius: 72, borderWidth: 3, borderColor: COLORS.mint, backgroundColor: '#153e33'},
  faceRing: {width: 120, height: 120, borderRadius: 60, borderWidth: 2, borderColor: '#d8f8eb', alignItems: 'center', justifyContent: 'center', overflow: 'hidden'},
  faceHead: {width: 52, height: 64, borderRadius: 26, borderWidth: 2, borderColor: COLORS.mintStrong, alignItems: 'center', justifyContent: 'center', marginTop: -10},
  faceSmile: {width: 18, height: 9, borderBottomWidth: 2, borderColor: COLORS.mintStrong, borderRadius: 9, marginTop: 25},
  faceShoulders: {position: 'absolute', width: 88, height: 46, borderRadius: 44, borderWidth: 2, borderColor: COLORS.mintStrong, bottom: -27},
  gazeTargetGlow: {position: 'absolute', top: 39, width: 42, height: 42, borderRadius: 21, borderWidth: 2, borderColor: COLORS.champagne, backgroundColor: '#8cbfac33'},
  gazeTarget: {position: 'absolute', top: 46, width: 28, height: 28, borderRadius: 14, borderWidth: 2, borderColor: '#f8e5b7', backgroundColor: '#123c31', alignItems: 'center', justifyContent: 'center'},
  gazeTargetDot: {width: 8, height: 8, borderRadius: 4, backgroundColor: COLORS.champagne},
  calibrationTitle: {color: COLORS.text, fontSize: 15, fontWeight: '800', textAlign: 'center'}, calibrationNote: {color: COLORS.textMuted, fontSize: 10, marginTop: 4, textAlign: 'center'},
  gazeInstruction: {color: COLORS.mint, fontSize: 10, fontWeight: '900', letterSpacing: 0.9, marginTop: 7, textAlign: 'center'},
  rollingPrompt: {color: COLORS.champagne, fontSize: 11, fontWeight: '700', minHeight: 18, marginTop: 7, textAlign: 'center', width: '100%'},
  captureDots: {flexDirection: 'row', gap: 8, marginTop: 14}, captureDot: {width: 8, height: 8, borderRadius: 4, backgroundColor: '#35534a'}, captureDotDone: {backgroundColor: COLORS.champagne},
  calibrationTrack: {width: '100%', height: 7, borderRadius: 99, backgroundColor: '#1a3b32', overflow: 'hidden', marginTop: 13}, calibrationFill: {height: 7, borderRadius: 99, backgroundColor: COLORS.mint}, calibrationProgress: {color: COLORS.mint, fontSize: 11, fontWeight: '800', marginTop: 7},
  calibrationError: {backgroundColor: '#452921', borderRadius: 16, borderColor: '#79483a', borderWidth: 1, padding: 14, marginVertical: 10}, calibrationErrorTitle: {color: '#ffd1bf', fontSize: 13, fontWeight: '900'}, calibrationErrorText: {color: '#d9aaa0', fontSize: 11, lineHeight: 16, marginTop: 4},
  privacyPoint: {flexDirection: 'row', gap: 11, paddingVertical: 11, borderTopColor: '#193d33', borderTopWidth: StyleSheet.hairlineWidth}, privacyCheck: {width: 25, height: 25, borderRadius: 9, backgroundColor: '#194a3d', alignItems: 'center', justifyContent: 'center'}, privacyCheckText: {color: COLORS.mint, fontWeight: '900'},
  selectionSummary: {flexDirection: 'row', alignItems: 'center', gap: 13, backgroundColor: '#123c31', borderRadius: 18, padding: 16, marginBottom: 12}, selectionCount: {color: COLORS.champagne, fontSize: 35, fontWeight: '300'}, selectionTitle: {color: COLORS.text, fontSize: 15, fontWeight: '800'}, selectionNote: {color: COLORS.textMuted, fontSize: 11, marginTop: 2},
  searchInput: {minHeight: 50, backgroundColor: COLORS.panel, borderRadius: 16, borderColor: COLORS.line, borderWidth: 1, color: COLORS.text, paddingHorizontal: 16, fontSize: 15, marginBottom: 14},
  appRow: {flexDirection: 'row', alignItems: 'center', gap: 11, minHeight: 61, borderTopColor: '#193d33', borderTopWidth: StyleSheet.hairlineWidth}, appInitial: {width: 36, height: 36, borderRadius: 12, alignItems: 'center', justifyContent: 'center', backgroundColor: '#1b493c'}, appInitialText: {color: COLORS.mint, fontSize: 15, fontWeight: '900'}, packageName: {color: COLORS.textMuted, fontSize: 10, marginTop: 2}, emptyText: {color: COLORS.textMuted, textAlign: 'center', paddingVertical: 30},
  privacyHero: {flexDirection: 'row', alignItems: 'center', gap: 14, borderRadius: 18, padding: 16, backgroundColor: '#153f34', marginBottom: 15}, privacyHeroIcon: {color: COLORS.champagne, fontSize: 31}, privacyHeroTitle: {color: COLORS.text, fontSize: 15, fontWeight: '800'}, privacyHeroText: {color: COLORS.textSoft, fontSize: 11, lineHeight: 16, marginTop: 2},
  optionLabel: {color: COLORS.textSoft, fontSize: 12, fontWeight: '800', marginTop: 16, marginBottom: 8}, chips: {flexDirection: 'row', flexWrap: 'wrap', gap: 8}, chip: {borderColor: COLORS.line, borderWidth: 1, borderRadius: 12, paddingHorizontal: 14, paddingVertical: 10, backgroundColor: '#0b241e'}, chipActive: {backgroundColor: COLORS.mint, borderColor: COLORS.mint}, chipText: {color: COLORS.textSoft, fontWeight: '800'}, chipTextActive: {color: COLORS.ink}, footnote: {color: COLORS.textMuted, fontSize: 10, lineHeight: 16, marginTop: 14},
  bottomNav: {minHeight: 70, flexDirection: 'row', backgroundColor: '#071f19', borderTopColor: '#1b4035', borderTopWidth: StyleSheet.hairlineWidth, paddingHorizontal: 5, paddingTop: 6},
  navItem: {flex: 1, alignItems: 'center', justifyContent: 'center', gap: 2}, navSymbol: {color: '#687f76', fontSize: 21, fontWeight: '700'}, navSymbolActive: {color: COLORS.mint}, navLabel: {color: '#687f76', fontSize: 10, fontWeight: '700'}, navLabelActive: {color: COLORS.mint}, navBadge: {position: 'absolute', top: -4, right: -11, minWidth: 16, height: 16, borderRadius: 8, backgroundColor: COLORS.amber, alignItems: 'center', justifyContent: 'center'}, navBadgeText: {color: COLORS.ink, fontSize: 9, fontWeight: '900'},
});
