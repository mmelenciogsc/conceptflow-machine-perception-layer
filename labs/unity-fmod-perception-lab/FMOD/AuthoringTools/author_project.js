/* SPDX-License-Identifier: MIT OR Apache-2.0 */
/* One-way creation from the official FMOD Studio 2.03.14 Examples template. */

function fail(message) { throw new Error("[MPL_FMOD_AUTHOR] " + message); }
function requireValue(value, message) { if (!value) fail(message); return value; }
function deleteAll(entityName, keep) {
    var entity=studio.project.model[entityName]; if(!entity) return;
    entity.findInstances().slice().forEach(function(item){ if(!keep||!keep(item)) requireValue(studio.project.deleteObject(item),"Could not delete "+entityName); });
}
function parameter(workspace,name) {
    return requireValue(workspace.addGameParameter({name:name,type:studio.project.parameterType.User,min:0,max:1}),"Could not create parameter "+name);
}
function attachParameter(event,preset) {
    var proxy=requireValue(event.addGameParameter(preset),"Could not attach parameter "+preset.name); proxy.initialValue=0; return preset;
}
function pluginParameter(effect,name) {
    var matches=effect.plugin.pluginParameters.filter(function(item){return item.name===name;});
    if(matches.length!==1) fail("Expected plugin parameter "+name); return matches[0];
}
function curve(owner,property,preset,points) {
    var automator=requireValue(owner.addAutomator(property),"Could not add automator "+property);
    var automation=requireValue(automator.addAutomationCurve(preset),"Could not add automation curve");
    points.forEach(function(point){requireValue(automation.addAutomationPoint(point[0],point[1]),"Could not add automation point");});
}
function importAsset(pathSuffix) {
    var projectDirectory=studio.project.filePath.replace(/[/\\][^/\\]+$/,"");
    requireValue(studio.project.importAudioFile(projectDirectory+"/Assets/SoundBubble/"+pathSuffix),"Could not import "+pathSuffix);
    var matches=studio.project.model.AudioFile.findInstances().filter(function(item){return item.getAssetPath()==="SoundBubble/"+pathSuffix;});
    if(matches.length!==1) fail("Expected imported asset "+pathSuffix); return matches[0];
}
function makeEvent(folder,bank,bus,parameters,name,trackName,audioFile,maxInstances,field) {
    var event=studio.project.workspace.addEvent(name,false); event.folder=folder; event.relationships.banks.add(bank);
    event.mixerInput.output=bus; event.masterTrack.mixerGroup.maxInstances=maxInstances;
    event.note=(field?"Distributed bounded envelopment emitter.":"Sharply localized nearest-surface anchor.")+" Provisional calibration asset; no spatial accuracy or comfort claim.";
    var proximity=attachParameter(event,parameters.proximity); attachParameter(event,parameters.motion); attachParameter(event,parameters.soundSize);
    var envelopment=field?attachParameter(event,parameters.envelopment):null;
    var track=event.addGroupTrack(trackName); track.mixerGroup.output=event.mixer.masterBus;
    var source=requireValue(studio.project.workspace.createPlugin("Resonance Audio Source"),"Missing Resonance Audio Source"); source.owner=track.mixerGroup.effectChain;
    pluginParameter(source,"Spread").value=field?12:0; pluginParameter(source,"Near-Field FX").value=false; pluginParameter(source,"Bypass Room").value=true;
    var sound=requireValue(track.addSound(event.timeline,"SingleSound",0,0.82),"Could not add sound"); sound.audioFile=audioFile; sound.setFadeInCurve(.04,-.25); sound.setFadeOutCurve(.12,-.2);
    curve(track.mixerGroup,"volume",proximity,field?[[0,-30],[.5,-22],[1,-14]]:[[0,-12],[.5,-10],[1,-9]]);
    if(field) curve(pluginParameter(source,"Spread"),"value",envelopment,[[0,12],[.4,55],[1,135]]);
    if(!event.is3D()) fail(name+" is not a 3D event"); return event;
}

if(studio.version.productVersion!==2||studio.version.majorVersion!==3||studio.version.minorVersion!==14) fail("FMOD Studio 2.03.14 is required");
if(!studio.project.lookup("event:/Ambience/Forest")||studio.project.lookup("event:/MachinePerception/SoundBubble/IntrusionAnchor")) fail("Expected untouched official Examples template");
deleteAll("Event"); deleteAll("Snapshot"); deleteAll("SnapshotGroup"); deleteAll("PluginEffect"); deleteAll("MixerReturn"); deleteAll("MixerGroup"); deleteAll("ParameterPreset"); deleteAll("ParameterPresetFolder"); deleteAll("EffectPreset"); deleteAll("EventFolder");
deleteAll("Bank",function(item){return item.isMasterBank||item.name==="Master";}); deleteAll("AudioFile"); deleteAll("EncodableAsset"); deleteAll("AudioTable"); deleteAll("DataFile"); deleteAll("DAWAsset"); deleteAll("DAWProject"); deleteAll("ProfilerSession"); deleteAll("SandboxScene"); deleteAll("UiMixerView"); deleteAll("MixerVCA"); deleteAll("Locale",function(item){return item.name==="English";});
var workspace=studio.project.workspace; workspace.builtBanksOutputDirectory="Build"; workspace.builtBanksSeparateAssets=false; workspace.builtBanksSeparateStreams=false; workspace.builtBanksIncludeFileNames=false; workspace.builtBanksIncludeReferencedEvents=true; workspace.builtBanksIncludeHash=false;
var parameters={proximity:parameter(workspace,"BubbleProximity"),motion:parameter(workspace,"MotionIntensity"),soundSize:parameter(workspace,"SoundSize"),envelopment:parameter(workspace,"Envelopment")};
var mobile=workspace.platforms.filter(function(item){return item.name==="Mobile"||item.name==="HTML5";}); if(mobile.length!==1) fail("Expected one Mobile/HTML5 platform"); mobile[0].name="Mobile"; mobile[0].subDirectory="Mobile";
var listener=requireValue(workspace.createPlugin("Resonance Audio Listener"),"Missing Resonance Audio Listener"); listener.owner=workspace.mixer.masterBus.effectChain;
var accessible=studio.project.create("MixerGroup"); accessible.name="Accessible Sonification"; accessible.output=workspace.mixer.masterBus; accessible.maxInstances=10;
var limiter=studio.project.create("LimiterEffect"); limiter.owner=accessible.effectChain;
var bubble=studio.project.create("MixerGroup"); bubble.name="Sound Bubble"; bubble.output=accessible; bubble.maxInstances=8;
var machineFolder=studio.project.create("EventFolder"); machineFolder.name="MachinePerception"; machineFolder.folder=workspace.masterEventFolder;
var bubbleFolder=studio.project.create("EventFolder"); bubbleFolder.name="SoundBubble"; bubbleFolder.folder=machineFolder;
var bank=studio.project.create("Bank"); bank.name="MachinePerception"; bank.folder=workspace.masterBankFolder;
makeEvent(bubbleFolder,bank,bubble,parameters,"IntrusionAnchor","Intrusion Anchor",importAsset("intrusion_anchor.wav"),2,false);
makeEvent(bubbleFolder,bank,bubble,parameters,"EnvelopmentField","Envelopment Field",importAsset("intrusion_field.wav"),6,true);
requireValue(studio.project.save(),"Could not save project");
console.log("[MPL_FMOD_AUTHOR] status=Created events=2 limiter=True resonanceSources=2 resonanceListener=True tuning=ProvisionalUnvalidated");
