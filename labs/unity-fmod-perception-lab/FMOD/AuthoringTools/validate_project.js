/* SPDX-License-Identifier: MIT OR Apache-2.0 */
function fail(message){throw new Error("[MPL_FMOD_VALIDATE] "+message);}
function requireCondition(condition,message){if(!condition)fail(message);}
function lookup(path){var value=studio.project.lookup(path);requireCondition(!!value,"Missing "+path);return value;}
function sources(event){return event.groupTracks[0].mixerGroup.effectChain.effects.filter(function(item){return item.entity==="PluginEffect"&&item.plugin.identifier==="Resonance Audio Source";});}
function parameterByName(event,name){var matches=event.getParameterPresets().filter(function(item){return item.presetOwner.name===name;});requireCondition(matches.length===1,"Expected one parameter "+name);return matches[0];}
function requireCurve(automatable,parameter,expectedPoints,label){
    var matches=[];
    automatable.automators.forEach(function(automator){
        automator.automationCurves.forEach(function(candidate){
            if(!!candidate.parameter&&candidate.parameter.id===parameter.id)matches.push(candidate);
        });
    });
    requireCondition(matches.length===1,label+" requires exactly one parameter-bound automation curve");
    var points=matches[0].automationPoints.slice().sort(function(left,right){return left.position-right.position;});
    requireCondition(points.length===expectedPoints.length,label+" point count invalid");
    expectedPoints.forEach(function(expected,index){requireCondition(Math.abs(points[index].position-expected[0])<0.000001&&Math.abs(points[index].value-expected[1])<0.000001,label+" point "+index+" invalid");});
}
requireCondition(studio.version.productVersion===2&&studio.version.majorVersion===3&&studio.version.minorVersion===14,"FMOD Studio 2.03.14 required");
var anchor=lookup("event:/MachinePerception/SoundBubble/IntrusionAnchor"); var field=lookup("event:/MachinePerception/SoundBubble/EnvelopmentField");
var focused=lookup("event:/MachinePerception/AuditoryIcons/FocusedObject"); var interfaceState=lookup("event:/MachinePerception/Interface/State");
var bank=lookup("bank:/MachinePerception"); var bubble=lookup("bus:/Accessible Sonification/Sound Bubble"); var accessible=lookup("bus:/Accessible Sonification");
var icons=lookup("bus:/Accessible Sonification/Focused Object Icons"); var interfaceBus=lookup("bus:/Accessible Sonification/Interface");
requireCondition(anchor.is3D()&&field.is3D(),"Events must be 3D"); requireCondition(anchor.groupTracks.length===1&&field.groupTracks.length===1,"Each event requires one layer track");
requireCondition(anchor.groupTracks[0].mixerGroup.name==="Intrusion Anchor"&&field.groupTracks[0].mixerGroup.name==="Envelopment Field","Layer names differ");
requireCondition(anchor.mixerInput.output.id===bubble.id&&field.mixerInput.output.id===bubble.id,"Events must route to Sound Bubble bus"); requireCondition(bubble.output.id===accessible.id,"Bus hierarchy invalid");
requireCondition(anchor.banks.length===1&&anchor.banks[0].id===bank.id&&field.banks.length===1&&field.banks[0].id===bank.id,"Bank assignment invalid");
requireCondition(anchor.masterTrack.mixerGroup.maxInstances===2&&field.masterTrack.mixerGroup.maxInstances===6,"Voice limits invalid");
requireCondition(focused.is3D(),"FocusedObject must be 3D"); requireCondition(!interfaceState.is3D(),"Interface State must be nonspatial");
requireCondition(focused.groupTracks.length===1&&interfaceState.groupTracks.length===1,"Focused and interface events require one track");
requireCondition(focused.masterTrack.mixerGroup.maxInstances===1&&icons.maxInstances===1,"FocusedObject event and bus must be max-one");
requireCondition(interfaceState.masterTrack.mixerGroup.maxInstances===2&&interfaceBus.maxInstances===2,"Interface voice limits invalid");
requireCondition(focused.mixerInput.output.id===icons.id&&interfaceState.mixerInput.output.id===interfaceBus.id,"Focused/interface routes invalid");
requireCondition(icons.output.id===accessible.id&&interfaceBus.output.id===accessible.id,"Accessible bus hierarchy invalid");
requireCondition(focused.banks.length===1&&focused.banks[0].id===bank.id&&interfaceState.banks.length===1&&interfaceState.banks[0].id===bank.id,"Focused/interface bank assignment invalid");
parameterByName(focused,"IconConcept"); var iconSalience=parameterByName(focused,"IconSalience"); parameterByName(focused,"IconConfidence");
parameterByName(focused,"DistanceMeters"); var dwellSpeech=parameterByName(focused,"DwellSpeechActive"); parameterByName(interfaceState,"InterfaceState");
var anchorSources=sources(anchor); var fieldSources=sources(field);
var focusedSources=sources(focused);
requireCondition(anchorSources.length===1&&fieldSources.length===1&&focusedSources.length===1,"Each spatial event requires one Resonance source");
requireCondition(sources(interfaceState).length===0,"Interface event must not have a spatializer");
requireCurve(anchor.groupTracks[0].mixerGroup,parameterByName(anchor,"BubbleProximity"),[[0,-12],[.5,-10],[1,-9]],"Anchor level");
requireCurve(field.groupTracks[0].mixerGroup,parameterByName(field,"BubbleProximity"),[[0,-30],[.5,-22],[1,-14]],"Field level");
requireCurve(focused.groupTracks[0].mixerGroup,iconSalience,[[0,-18],[.5,-13],[1,-10]],"Focused salience level");
requireCurve(focused.groupTracks[0].mixerGroup,dwellSpeech,[[0,0],[1,-12]],"Focused dwell ducking");
var fieldSpread=fieldSources[0].plugin.pluginParameters.filter(function(item){return item.name==="Spread";})[0];
requireCondition(!!fieldSpread,"Missing field Spread parameter");
requireCurve(fieldSpread,parameterByName(field,"Envelopment"),[[0,12],[.4,55],[1,135]],"Field spread");
requireCondition(studio.project.model.PluginEffect.findInstances().filter(function(item){return item.plugin.identifier==="Resonance Audio Listener";}).length===1,"Expected one Resonance listener");
requireCondition(studio.project.model.LimiterEffect.findInstances().length===1,"Expected one limiter");
requireCondition(studio.project.model.AudioFile.findInstances().length===13&&studio.project.model.SingleSound.findInstances().length===13,"Expected thirteen original procedural assets and sounds");
requireCondition(studio.project.model.Event.findInstances().length===4,"Unexpected events remain");
console.log("[MPL_FMOD_VALIDATE] status=Pass events=4 anchorVoices=2 fieldVoices=6 focusedObjectMax=1 interfaceSpatial=False limiter=True resonanceSources=3 banksNotRedistributed=True perceptualValidation=False");
