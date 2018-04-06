/**
 *  Xiaomi Mi Cube Controller - model MFKZQ01LM
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.1b
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Based on original SmartThings device handler code by Oleg "DroidSector" Smirnov and Artur Draga
 *  With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, rinkek, ronvandegraaf, snalee, tmleafs, twonk, & veeceeoh
 *  Reworked and additional code for use with Hubitat Elevation hub by veeceeoh
 *
 *  Known issues:
 *  + Xiaomi devices send reports based on changes, and a status report every 50-60 minutes. These settings cannot be adjusted.
 *  + The battery level / voltage is not reported at pairing. Wait for the first status report, 50-60 minutes after pairing.
 *  + Pairing Xiaomi devices can be difficult as they were not designed to use with a Hubitat hub.
 *    Holding the Cube's reset button (inside, next to the battery) until the LED blinks will start pairing mode.
 *    3 quick flashes indicates success, while one long flash means pairing has not started yet.
 *    In either case, keep the sensor "awake" by short-pressing the reset button repeatedly, until recognized by Hubitat.
 *  + The connection can be dropped without warning. To reconnect, put Hubitat in "Discover Devices" mode, and follow
 *    the same steps for pairing. As long as it has not been removed from the Hubitat's device list, when the LED
 *    flashes 3 times, the sensor should be reconnected and will resume reporting as normal
 *
 */

metadata {
	definition (name: "Xiaomi Mi Cube Controller", namespace: "veeceeoh", author: "veeceeoh") {
		capability "Actuator"
		capability "Button"
		capability "Configuration"
		capability "Battery"
		capability "Three Axis" //Simulated!
		capability "Sensor"

		attribute "face", "number"
		attribute "angle", "number"
		attribute "lastCheckin", "String"
		attribute "lastCheckinDate", "String"
		attribute "batteryLastReplaced", "String"

		// Fingerprint data taken from ZiGate webpage http://zigate.fr/xiaomi-magic-cube-cluster
		fingerprint endpointId: "01", profileId: "0104", deviceId: "5F01", inClusters: "0000, 0003, 0012, 0019", outClusters: "0000, 0003, 0012, 0019", manufacturer: "LUMI", model: "lumi.sensor_cube"
		fingerprint endpointId: "01", inClusters: "0000, 0003, 0012, 0019", outClusters: "0000, 0003, 0012, 0019", manufacturer: "LUMI", model: "lumi.sensor_cube"
		fingerprint endpointId: "01", profileId: "0104", deviceId: "5F01", inClusters: "0000, 0003, 0019", outClusters: "0000, 0003, 0019", manufacturer: "LUMI", model: "lumi.sensor_cube"
		fingerprint endpointId: "01", inClusters: "0000, 0003, 0019", outClusters: "0000, 0003, 0019", manufacturer: "LUMI", model: "lumi.sensor_cube"
		fingerprint profileId: "0104", deviceId: "5F01", inClusters: "0000, 0003, 0012, 0019", outClusters: "0000, 0003, 0012, 0019", manufacturer: "LUMI", model: "lumi.sensor_cube"

		command "setFace0"
		command "setFace1"
		command "setFace2"
		command "setFace3"
		command "setFace4"
		command "setFace5"

		command "flip90"
		command "flip180"
		command "slide"
		command "knock"
		command "rotateR"
		command "rotateL"
		command "shake"

		command "enrollResponse"
		command "resetBatteryReplacedDate"
	}

	preferences {
		input (name: "cubeMode", title: "Cube Mode: Select how many buttons to control", description: "", type: "enum", options: [0: "Simple - 7 buttons", 1: "Advanced - 36 buttons", 2: "Combined - 43 buttons"])
		input name: "dateformat", type: "enum", title: "Date Format for lastCheckin: US (MDY), UK (DMY), or Other (YMD)", description: "", options:["US","UK","Other"]
		input name: "clockformat", type: "bool", title: "Use 24 hour clock", description: ""
		//Battery Reset Config
		input name: "voltsmin", title: "Min Volts (0% battery = ___ volts, range 2.0 to 2.7)", type: "decimal", range: "2..2.7", defaultValue: 2.5
		input name: "voltsmax", title: "Max Volts (100% battery = ___ volts, range 2.8 to 3.4)", type: "decimal", range: "2.8..3.4", defaultValue: 3
		//Debug logging Config
		input name: "debugLogging", type: "bool", title: "Display debug log messages", description: "", defaultValue: false
	}
}

def setFace0() { setFace(0) }
def setFace1() { setFace(1) }
def setFace2() { setFace(2) }
def setFace3() { setFace(3) }
def setFace4() { setFace(4) }
def setFace5() { setFace(5) }
def flip90() {
	def flipMap = [0:5, 1:2, 2:0, 3:2, 4:5, 5:3]
	flipEvents(flipMap[device.currentValue("face") as Integer], "90")
}
def flip180() {
	def flipMap = [0:3, 1:4, 2:5, 3:0, 4:1, 5:2]
	flipEvents(flipMap[device.currentValue("face") as Integer], "180")
}
def rotateL() { rotateEvents(-90) }
def rotateR() { rotateEvents(90) }
def slide() { slideEvents(device.currentValue("face") as Integer) }
def knock() { knockEvents(device.currentValue("face") as Integer) }
def shake() { shakeEvents() }

def setFace(Integer faceId) {
	def Integer prevFaceId = device.currentValue("face")
	if (prevFaceId == faceId) {
		flipEvents(faceId, "0")
	} else if ((prevFaceId == 0 && faceId == 3)||(prevFaceId == 1 && faceId == 4)||(prevFaceId == 2 && faceId == 5)||(prevFaceId == 3 && faceId == 0)||(prevFaceId == 4 && faceId == 1)||(prevFaceId == 5 && faceId == 2)){
		flipEvents(faceId, "180")
	} else {
		flipEvents(faceId, "90")
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	def cluster = description.split(",").find {it.split(":")[0].trim() == "cluster"}?.split(":")[1].trim()
	def attrId = description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
	def valueHex = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
	displayDebugLog("Parsing description: ${description}")

	// Determine current time and date in the user-selected date format and clock style
	def now = formatDate()
	def nowDate = new Date(now).getTime()

	// lastCheckin and lastPressedDate can be used to determine if the sensor is "awake" and connected
	sendEvent(name: "lastCheckin", value: now)
	sendEvent(name: "lastCheckinDate", value: nowDate)

	Map map = [:]

	// Send message data to appropriate parsing function based on the type of report
	if (cluster == "0006") {
		map = parseButtonMessage(Integer.parseInt(valueHex))
	} else if (cluster == "0000" & attrId == "0005") {
		displayDebugLog("Reset button was short-pressed")
		map = (valueHex.size() > 60) ? parseBattery(valueHex.split('FF42')[1]) : [:]
	} else if (cluster == "0000" & (attrId == "FF01" || attrId == "FF02")) {
		map = (valueHex.size() > 30) ? parseBattery(valueHex) : [:]
	} else if (!(cluster == "0000" & attrId == "0001")) {
		displayDebugLog("Unable to parse ${description}")
	}

	if (description?.startsWith('catchall:')) {
		parseCatchAllMessage(description)
	}
	else if (description?.startsWith('read attr -')) {
		parseReportAttributeMessage(description)
	}

	if (map) {
		displayDebugLog(map.descriptionText)
		return createEvent(map)
	} else
		return [:]
}

// Convert raw 4 digit integer voltage value into percentage based on minVolts/maxVolts range
private parseBattery(description) {
	displayDebugLog("${device.displayName}: Battery parse string = ${description}")
	def MsgLength = description.size()
	def rawValue
	for (int i = 4; i < (MsgLength-3); i+=2) {
		if (description[i..(i+1)] == "21") { // Search for byte preceeding battery voltage bytes
			rawValue = Integer.parseInt((description[(i+4)..(i+5)] + description[(i+2)..(i+3)]),16)
			break
		}
	}
	def rawVolts = rawValue / 1000
	def minVolts = voltsmin ? voltsmin : 2.5
	def maxVolts = voltsmax ? voltsmax : 3.0
	def pct = (rawVolts - minVolts) / (maxVolts - minVolts)
	def roundedPct = Math.min(100, Math.round(pct * 100))
	def result = [
		name: 'battery',
		value: roundedPct,
		unit: "%",
		isStateChange: true,
		descriptionText: "${device.displayName}: Battery level is ${roundedPct}%, raw battery is ${rawVolts}V"
	]
	return result
}

private Map parseReportAttributeMessage(String description) {
	Map descMap = (description - "read attr - ").split(",").inject([:]) {
		map, param ->
		def nameAndValue = param.split(":")
		map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
	}
	log.debug "Cluster: ${descMap.cluster}, Attribute ID: ${descMap.attrId}, Value: ${descMap.value}"
	if (descMap.cluster == "0012" && descMap.attrId == "0055") { // Shake, flip, knock, slide
		log.debug "Shake, flip, knock, or slide detected"
		getMotionResult(descMap.value)
	} else if (descMap.cluster == "000C" && descMap.attrId == "ff05") { // Rotation (90 and 180 degrees)
		log.debug "Rotation detected"
		getRotationResult(descMap.value)
	} else {
		log.debug "Unknown event - Cluster: ${descMap.cluster}, Attribute ID: ${descMap.attrId}, Value: ${descMap.value}"
	}
}

// def String hexToBinOld(String thisByte) {
// 	return String.format("%8s", Integer.toBinaryString(Integer.parseInt(thisByte,16))).replace(' ', '0')
// }

def String hexToBin(String thisByte, Integer size = 8) {
	String binaryValue = new BigInteger(thisByte, 16).toString(2);
	return String.format("%${size}s", binaryValue).replace(' ', '0')
}

private Map getMotionResult(String value) {
	String motionType = value[0..1]
	String binaryValue = hexToBin(value[2..3])
	Integer sourceFace = Integer.parseInt(binaryValue[2..4],2)
	Integer targetFace = Integer.parseInt(binaryValue[5..7],2)
	log.debug "motionType: ${motionType}, binaryValue: ${binaryValue}, sourceFace: ${sourceFace}, targetFace: ${targetFace}"
	if (motionType == "00") {
		switch(binaryValue[0..1]) {
			case "00":
				if (targetFace==0) { shakeEvents() }
				break
			case "01":
				flipEvents(targetFace, "90")
				break
			case "10":
				flipEvents(targetFace, "180")
				break
		}
	} else if (motionType == "01") {
		slideEvents(targetFace)
	} else if (motionType == "02") {
		knockEvents(targetFace)
	}
}

private Map getRotationResult(value) {
	Integer angle = Math.round(Float.intBitsToFloat(Long.parseLong(value[0..7],16).intValue()));
	rotateEvents(angle)
}

def Map shakeEvents() {
	if (!settings.cubeMode || settings.cubeMode in ['0','2'] ) {
		sendEvent([
			name: "button",
			value: "pushed",
			data: [buttonNumber: 1, face: device.currentValue("face")],
			descriptionText: (settings.cubeMode == '0') ? "$device.displayName was shaken" : null,
			isStateChange: true,
			displayed: (settings.cubeMode == '0') ? true : false
		])
	}

	if (settings.cubeMode in ['1','2'] ){
		sendEvent([
			name: "button",
			value: "pushed",
			data: [buttonNumber: (device.currentValue("face") as Integer) + ((settings.cubeMode == '1') ? 31 : 38),
			face: device.currentValue("face")],
			descriptionText: "$device.displayName was shaken (Face # ${device.currentValue("face")}).",
			isStateChange: true])
	}
}

def flipEvents(Integer faceId, String flipType) {
	if (flipType == "0") {
		sendEvent( [name: 'face', value: -1, isStateChange: false] )
		sendEvent( [name: 'face', value: faceId, isStateChange: false] )
	} else if (flipType == "90") {
		if (settings.cubeMode in ['0','2']) {
			sendEvent( [
				name: 'button',
				value: "pushed" ,
				data: [buttonNumber: 2, face: faceId],
				descriptionText: (settings.cubeMode == '0') ? "$device.displayName detected $flipType degree flip" : null,
				isStateChange: true,
				displayed: (settings.cubeMode == '0') ? true : false
			] )
		}
	} else if (flipType == "180") {
		if (settings.cubeMode in ['0','2']) {
			sendEvent( [
				name: 'button',
				value: "pushed" ,
				data: [buttonNumber: 3, face: faceId],
				descriptionText: (settings.cubeMode == '0') ? "$device.displayName detected $flipType degree flip" : null,
				isStateChange: true,
				displayed: (settings.cubeMode == '0') ? true : false
			] )
		}
	}
	sendEvent( [name: 'face', value: faceId, isStateChange: true, displayed: false ] )
	if (settings.cubeMode in ['1','2']) {
		sendEvent( [
			name: "button",
			value: "pushed",
			data: [buttonNumber: faceId+((settings.cubeMode == '1') ? 1 : 8), face: faceId],
			descriptionText: "$device.displayName was fliped to face # $faceId",
			isStateChange: true
	   ] )
	}
	switch (faceId) {
		case 0: sendEvent( [ name: "threeAxis", value: "0,-1000,0", isStateChange: true, displayed: false] ); break
		case 1: sendEvent( [ name: "threeAxis", value: "-1000,0,0", isStateChange: true, displayed: false] ); break
		case 2: sendEvent( [ name: "threeAxis", value: "0,0,1000", isStateChange: true, displayed: false] ); break
		case 3: sendEvent( [ name: "threeAxis", value: "1000,0,0", isStateChange: true, displayed: false] ); break
		case 4: sendEvent( [ name: "threeAxis", value: "0,1000,0", isStateChange: true, displayed: false] ); break
		case 5: sendEvent( [ name: "threeAxis", value: "0,0,-1000", isStateChange: true, displayed: false] ); break
	}
}

def Map slideEvents(Integer targetFace) {
	if ( targetFace != device.currentValue("face") as Integer ) { log.info "Stale face data, updating."; setFace(targetFace) }
	if (!settings.cubeMode || settings.cubeMode in ['0','2'] ) {
		sendEvent( [
			name: "button",
			value: "pushed",
			data: [buttonNumber: 4, face: targetFace],
			descriptionText: (settings.cubeMode == '0') ? "$device.displayName detected slide motion." : null,
			isStateChange: true,
			displayed: (settings.cubeMode == '0') ? true : false
		]  )
	}

	if ( settings.cubeMode in ['1','2'] ) {
		sendEvent( [
			name: "button",
			value: "pushed",
			data: [buttonNumber: targetFace+((settings.cubeMode == '1') ? 7 : 14), face: targetFace],
			descriptionText: "$device.displayName was slid with face # $targetFace up.",
			isStateChange: true
		] ) }
}

def knockEvents(Integer targetFace) {
	if ( targetFace != device.currentValue("face") as Integer ) { log.info "Stale face data, updating."; setFace(targetFace) }
	if (!settings.cubeMode || settings.cubeMode in ['0','2'] ) {
		sendEvent( [
			name: "button",
			value: "pushed",
			data: [buttonNumber: 5, face: targetFace],
			descriptionText: (settings.cubeMode == '0') ? "$device.displayName detected knock motion." : null,
			isStateChange: true,
			displayed: (settings.cubeMode == '0') ? true : false
		] )
	}
	if ( settings.cubeMode in ['1','2'] ) {
		sendEvent( [
			name: "button",
			value: "pushed",
			data: [buttonNumber: targetFace+((settings.cubeMode == '1') ? 13 : 20), face: targetFace],
			descriptionText: "$device.displayName was knocked with face # $targetFace up",
			isStateChange: true
		] )
	 }
}

def rotateEvents(Integer angle) {
	sendEvent( [ name: "angle", value: angle, isStateChange: true, displayed: false] )
	if ( angle > 0 ) {
		if (!settings.cubeMode || settings.cubeMode in ['0','2'] ) {
			sendEvent( [
				name: "button",
				value: "pushed",
				data: [buttonNumber: 6, face: device.currentValue("face"), angle: angle],
				descriptionText: (settings.cubeMode == '0') ? "$device.displayName was rotated right." : null,
				isStateChange: true,
				displayed: (settings.cubeMode == '0') ? true : false
			] )
		}
		if ( settings.cubeMode in ['1','2'] ) {
			sendEvent( [
				name: "button",
				value: "pushed",
				data: [buttonNumber: (device.currentValue("face") as Integer) + ((settings.cubeMode == '1') ? 19 : 26), face: device.currentValue("face")],
				descriptionText: "$device.displayName was rotated right (Face # ${device.currentValue("face")}).",
				isStateChange: true
			] )
		}
	} else {
		if (!settings.cubeMode || settings.cubeMode in ['0','2'] ) {
			sendEvent( [
				name: "button",
				value: "pushed",
				data: [buttonNumber: 7, face: device.currentValue("face"), angle: angle],
				descriptionText: (settings.cubeMode == '0') ? "$device.displayName was rotated left." : null,
				isStateChange: true,
				displayed: (settings.cubeMode == '0') ? true : false
			] )
		}
		if ( settings.cubeMode in ['1','2'] ) {
			sendEvent( [
				name: "button",
				value: "pushed",
				data: [buttonNumber: (device.currentValue("face") as Integer) + ((settings.cubeMode == '1') ? 25 : 32), face: device.currentValue("face")],
				descriptionText: "$device.displayName was rotated left (Face # ${device.currentValue("face")}).",
				isStateChange: true
			] )
		}
	}
}

def reset() {
}

def initialize() {
	sendState()
}

def poll() {
	//sendState()
}

def sendState() {
	sendEvent(name: "numberOfButtons", value: 7)
}

def updated() {
	if ( state.lastUpdated && (now() - state.lastUpdated) < 500 ) return
	switch(settings.cubeMode) {
		case "1": sendEvent(name: "numberOfButtons", value: 36); break
		case "2": sendEvent(name: "numberOfButtons", value: 43); break
		default: sendEvent(name: "numberOfButtons", value: 7); break
	}
	state.lastUpdated = now()
}
