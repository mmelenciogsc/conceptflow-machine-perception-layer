/* SPDX-License-Identifier: MIT OR Apache-2.0 */
/* One-way creation from the official FMOD Studio 2.03.14 Examples template. */

function fail(message) { throw new Error("[MPL_FMOD_AUTHOR] " + message); }
function requireValue(value, message) { if (!value) fail(message); return value; }
function deleteAll(entityName, keep) {
    var entity=studio.project.model[entityName]; if(!entity) return;
    entity.findInstances().slice().forEach(function(item){ if(!keep||!keep(item)) requireValue(studio.project.deleteObject(item),"Could not delete "+entityName); });
}
function parameter(workspace,name,min,max,type) {
    return requireValue(workspace.addGameParameter({name:name,type:type||studio.project.parameterType.User,min:min===undefined?0:min,max:max===undefined?1:max}),"Could not create parameter "+name);
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
function importAsset(folder,pathSuffix) {
    var projectDirectory=studio.project.filePath.replace(/[/\\][^/\\]+$/,"");
    requireValue(studio.project.importAudioFile(projectDirectory+"/Assets/"+folder+"/"+pathSuffix),"Could not import "+pathSuffix);
    var matches=studio.project.model.AudioFile.findInstances().filter(function(item){return item.getAssetPath()===folder+"/"+pathSuffix;});
    if(matches.length!==1) fail("Expected imported asset "+pathSuffix); return matches[0];
}
function localDiscreteParameter(event,name,maxExclusive) {
    var value=requireValue(event.addGameParameter({name:name,type:studio.project.parameterType.UserDiscrete,min:0,max:maxExclusive}),"Could not create "+name);
    value.initialValue=0; return value;
}
function makeFocusedEvent(folder,bank,bus,parameters) {
    var event=studio.project.workspace.addEvent("FocusedObject",false); event.folder=folder; event.relationships.banks.add(bank);
    event.mixerInput.output=bus; event.masterTrack.mixerGroup.maxInstances=1;
    event.note="At most one focused-object auditory icon. Android supplies HEAD or WORLD coordinates; Camera coordinates fail closed. Provisional calibration asset.";
    var concept=localDiscreteParameter(event,"IconConcept",5);
    var salience=attachParameter(event,parameters.iconSalience); attachParameter(event,parameters.iconConfidence);
    attachParameter(event,parameters.distanceMeters); attachParameter(event,parameters.beaconMode);
    var dwell=attachParameter(event,parameters.dwellSpeechActive);
    var track=event.addGroupTrack("Focused Object Icon"); track.mixerGroup.output=event.mixer.masterBus;
    var source=requireValue(studio.project.workspace.createPlugin("Resonance Audio Source"),"Missing focused-object Resonance Audio Source"); source.owner=track.mixerGroup.effectChain;
    pluginParameter(source,"Spread").value=0; pluginParameter(source,"Near-Field FX").value=false; pluginParameter(source,"Bypass Room").value=true;
    var icons=[
        ["neutral_presence.wav",.22],["soft_footfall_pair.wav",.32],["restrained_latch.wav",.26],
        ["short_freewheel.wav",.30],["subdued_tire_texture.wav",.30]
    ];
    icons.forEach(function(item,index){
        var sound=requireValue(track.addSound(concept,"SingleSound",index,1),"Could not add icon "+item[0]);
        sound.audioFile=importAsset("AuditoryIcons",item[0]); sound.setFadeInCurve(.01,-.2); sound.setFadeOutCurve(.04,-.2);
    });
    curve(track.mixerGroup,"volume",salience,[[0,-18],[.5,-13],[1,-10]]);
    curve(track.mixerGroup,"volume",dwell,[[0,0],[1,-12]]);
    if(!event.is3D()) fail("FocusedObject is not a 3D event"); return event;
}
function makeInterfaceEvent(folder,bank,bus) {
    var event=studio.project.workspace.addEvent("State",false); event.folder=folder; event.relationships.banks.add(bank);
    event.mixerInput.output=bus; event.masterTrack.mixerGroup.maxInstances=2;
    event.note="Nonspatial fixed interface-state earcons. No generated VQA answer content is accepted by this event.";
    var state=localDiscreteParameter(event,"InterfaceState",6);
    var track=event.addGroupTrack("Interface State"); track.mixerGroup.output=event.mixer.masterBus;
    var states=["closed","open","vqa","beacon","back","back_unavailable"];
    states.forEach(function(name,index){
        var sound=requireValue(track.addSound(state,"SingleSound",index,1),"Could not add interface state "+name);
        sound.audioFile=importAsset("Interface",name+".wav"); sound.setFadeInCurve(.008,-.2); sound.setFadeOutCurve(.04,-.2);
    });
    if(event.is3D()) fail("Interface State must remain nonspatial"); return event;
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
var parameters={proximity:parameter(workspace,"BubbleProximity"),motion:parameter(workspace,"MotionIntensity"),soundSize:parameter(workspace,"SoundSize"),envelopment:parameter(workspace,"Envelopment"),iconSalience:parameter(workspace,"IconSalience"),iconConfidence:parameter(workspace,"IconConfidence"),distanceMeters:parameter(workspace,"DistanceMeters",0,8),beaconMode:parameter(workspace,"BeaconMode",0,3,studio.project.parameterType.UserDiscrete),dwellSpeechActive:parameter(workspace,"DwellSpeechActive")};
var mobile=workspace.platforms.filter(function(item){return item.name==="Mobile"||item.name==="HTML5";}); if(mobile.length!==1) fail("Expected one Mobile/HTML5 platform"); mobile[0].name="Mobile"; mobile[0].subDirectory="Mobile";
var listener=requireValue(workspace.createPlugin("Resonance Audio Listener"),"Missing Resonance Audio Listener"); listener.owner=workspace.mixer.masterBus.effectChain;
var accessible=studio.project.create("MixerGroup"); accessible.name="Accessible Sonification"; accessible.output=workspace.mixer.masterBus; accessible.maxInstances=10;
var limiter=studio.project.create("LimiterEffect"); limiter.owner=accessible.effectChain;
var bubble=studio.project.create("MixerGroup"); bubble.name="Sound Bubble"; bubble.output=accessible; bubble.maxInstances=8;
var icons=studio.project.create("MixerGroup"); icons.name="Focused Object Icons"; icons.output=accessible; icons.maxInstances=1;
var interfaceBus=studio.project.create("MixerGroup"); interfaceBus.name="Interface"; interfaceBus.output=accessible; interfaceBus.maxInstances=2;
var machineFolder=studio.project.create("EventFolder"); machineFolder.name="MachinePerception"; machineFolder.folder=workspace.masterEventFolder;
var bubbleFolder=studio.project.create("EventFolder"); bubbleFolder.name="SoundBubble"; bubbleFolder.folder=machineFolder;
var iconFolder=studio.project.create("EventFolder"); iconFolder.name="AuditoryIcons"; iconFolder.folder=machineFolder;
var interfaceFolder=studio.project.create("EventFolder"); interfaceFolder.name="Interface"; interfaceFolder.folder=machineFolder;
var bank=studio.project.create("Bank"); bank.name="MachinePerception"; bank.folder=workspace.masterBankFolder;
makeEvent(bubbleFolder,bank,bubble,parameters,"IntrusionAnchor","Intrusion Anchor",importAsset("SoundBubble","intrusion_anchor.wav"),2,false);
makeEvent(bubbleFolder,bank,bubble,parameters,"EnvelopmentField","Envelopment Field",importAsset("SoundBubble","intrusion_field.wav"),6,true);
makeFocusedEvent(iconFolder,bank,icons,parameters);
makeInterfaceEvent(interfaceFolder,bank,interfaceBus);
requireValue(studio.project.save(),"Could not save project");
console.log("[MPL_FMOD_AUTHOR] status=Created events=4 focusedObjectMax=1 interfaceSpatial=False limiter=True resonanceSources=3 resonanceListener=True tuning=ProvisionalUnvalidated");
