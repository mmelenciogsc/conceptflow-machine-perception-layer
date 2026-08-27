/* SPDX-License-Identifier: MIT OR Apache-2.0 */
/* One-way migration for the previously generated two-event public project. */
function fail(message){throw new Error("[MPL_FMOD_UPGRADE] "+message);}
function requireValue(value,message){if(!value)fail(message);return value;}
function parameter(workspace,name,min,max){return requireValue(workspace.addGameParameter({name:name,type:studio.project.parameterType.User,min:min,max:max}),"Could not create "+name);}
function attachParameter(event,preset){var proxy=requireValue(event.addGameParameter(preset),"Could not attach "+preset.name);proxy.initialValue=0;return preset;}
function localDiscreteParameter(event,name,maxExclusive){var value=requireValue(event.addGameParameter({name:name,type:studio.project.parameterType.UserDiscrete,min:0,max:maxExclusive}),"Could not create "+name);value.initialValue=0;return value;}
function pluginParameter(effect,name){var matches=effect.plugin.pluginParameters.filter(function(item){return item.name===name;});if(matches.length!==1)fail("Expected plugin parameter "+name);return matches[0];}
function curve(owner,property,preset,points){var automator=requireValue(owner.addAutomator(property),"Could not add automator "+property);var automation=requireValue(automator.addAutomationCurve(preset),"Could not add automation curve");points.forEach(function(point){requireValue(automation.addAutomationPoint(point[0],point[1]),"Could not add automation point");});}
function importAsset(folder,name){var directory=studio.project.filePath.replace(/[/\\][^/\\]+$/,"");requireValue(studio.project.importAudioFile(directory+"/Assets/"+folder+"/"+name),"Could not import "+name);var matches=studio.project.model.AudioFile.findInstances().filter(function(item){return item.getAssetPath()===folder+"/"+name;});if(matches.length!==1)fail("Expected imported asset "+name);return matches[0];}
function folder(parent,name){var value=studio.project.create("EventFolder");value.name=name;value.folder=parent;return value;}

if(studio.version.productVersion!==2||studio.version.majorVersion!==3||studio.version.minorVersion!==14)fail("FMOD Studio 2.03.14 is required");
var existingAnchor=studio.project.lookup("event:/MachinePerception/SoundBubble/IntrusionAnchor");
if(!existingAnchor||!studio.project.lookup("event:/MachinePerception/SoundBubble/EnvelopmentField")||
   studio.project.lookup("event:/MachinePerception/AuditoryIcons/FocusedObject"))fail("Expected the generated two-event project exactly once");
var workspace=studio.project.workspace;
var accessible=requireValue(studio.project.lookup("bus:/Accessible Sonification"),"Missing accessible bus");
var bank=requireValue(studio.project.lookup("bank:/MachinePerception"),"Missing bank");
var machineFolder=requireValue(existingAnchor.folder.folder,"Missing event folder");
var iconSalience=parameter(workspace,"IconSalience",0,1), iconConfidence=parameter(workspace,"IconConfidence",0,1);
var distanceMeters=parameter(workspace,"DistanceMeters",0,8), dwellSpeechActive=parameter(workspace,"DwellSpeechActive",0,1);
var icons=studio.project.create("MixerGroup");icons.name="Focused Object Icons";icons.output=accessible;icons.maxInstances=1;
var interfaceBus=studio.project.create("MixerGroup");interfaceBus.name="Interface";interfaceBus.output=accessible;interfaceBus.maxInstances=2;
var iconFolder=folder(machineFolder,"AuditoryIcons"), interfaceFolder=folder(machineFolder,"Interface");

var focused=workspace.addEvent("FocusedObject",false);focused.folder=iconFolder;focused.relationships.banks.add(bank);focused.mixerInput.output=icons;focused.masterTrack.mixerGroup.maxInstances=1;
focused.note="At most one focused-object auditory icon. Android supplies HEAD or WORLD coordinates; Camera coordinates fail closed. Provisional calibration asset.";
var concept=localDiscreteParameter(focused,"IconConcept",5), salience=attachParameter(focused,iconSalience);attachParameter(focused,iconConfidence);attachParameter(focused,distanceMeters);var dwell=attachParameter(focused,dwellSpeechActive);
var focusedTrack=focused.addGroupTrack("Focused Object Icon");focusedTrack.mixerGroup.output=focused.mixer.masterBus;
var source=requireValue(workspace.createPlugin("Resonance Audio Source"),"Missing focused-object Resonance Audio Source");source.owner=focusedTrack.mixerGroup.effectChain;pluginParameter(source,"Spread").value=0;pluginParameter(source,"Near-Field FX").value=false;pluginParameter(source,"Bypass Room").value=true;
[["neutral_presence.wav",.22],["soft_footfall_pair.wav",.32],["restrained_latch.wav",.26],["short_freewheel.wav",.30],["subdued_tire_texture.wav",.30]].forEach(function(item,index){var sound=requireValue(focusedTrack.addSound(concept,"SingleSound",index,1),"Could not add "+item[0]);sound.audioFile=importAsset("AuditoryIcons",item[0]);sound.setFadeInCurve(.01,-.2);sound.setFadeOutCurve(.04,-.2);});
curve(focusedTrack.mixerGroup,"volume",salience,[[0,-18],[.5,-13],[1,-10]]);curve(focusedTrack.mixerGroup,"volume",dwell,[[0,0],[1,-12]]);if(!focused.is3D())fail("FocusedObject is not 3D");

var interfaceState=workspace.addEvent("State",false);interfaceState.folder=interfaceFolder;interfaceState.relationships.banks.add(bank);interfaceState.mixerInput.output=interfaceBus;interfaceState.masterTrack.mixerGroup.maxInstances=2;
interfaceState.note="Nonspatial fixed interface-state earcons. No generated VQA answer content is accepted by this event.";
var state=localDiscreteParameter(interfaceState,"InterfaceState",6), interfaceTrack=interfaceState.addGroupTrack("Interface State");interfaceTrack.mixerGroup.output=interfaceState.mixer.masterBus;
["closed","open","vqa","beacon","back","back_unavailable"].forEach(function(name,index){var sound=requireValue(interfaceTrack.addSound(state,"SingleSound",index,1),"Could not add "+name);sound.audioFile=importAsset("Interface",name+".wav");sound.setFadeInCurve(.008,-.2);sound.setFadeOutCurve(.04,-.2);});if(interfaceState.is3D())fail("Interface State must remain nonspatial");
requireValue(studio.project.save(),"Could not save project");
console.log("[MPL_FMOD_UPGRADE] status=Created eventsAdded=2 focusedObjectMax=1 interfaceSpatial=False");
