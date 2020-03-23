/**
 *  Xiaomi Smart Socket - model GMR4004CN
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.0.1
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
 *  Based on SmartThings device handler code by a4refillpad
 *  Reworked and additional code for use with Hubitat Elevation hub by veeceeoh
 *  With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, guyeeba, jmagnuson, mike.maxwell, rinkek, ronvandegraaf, snalee, tmleafs, twonk, veeceeoh, xtianpaiva, & vpjuslin
 *
 *  Basic control utilizes OnOff cluster (0x0006) commands off (0x00), on (0x01), and toggle (0x02)
 *
 *  Notes on capabilities of the different models:
 *  Model GMR4004CN
 *    - only model so far
 *
 *  Known issues:
 *  + Xiaomi devices send reports based on changes, and a status report every 50-60 minutes. These settings cannot be adjusted.
 *  + Pairing Xiaomi devices can be difficult as they were not designed to use with a Hubitat hub.
 *    Holding the sensor's reset button until the LED blinks will start pairing mode.
 *    3 quick flashes indicates success, while one long flash means pairing has not started yet.
 *    In either case, keep the sensor "awake" by short-pressing the reset button repeatedly, until recognized by Hubitat.
 *  + The connection can be dropped without warning. To reconnect, put Hubitat in "Discover Devices" mode, and follow
 *    the same steps for pairing. As long as it has not been removed from the Hubitat's device list, when the LED
 *    flashes 3 times, the sensor should be reconnected and will resume reporting as normal
 *
 */


metadata {
    definition (name: "Xiaomi Smart Socket", namespace: "veeceoh", author: "veeceeoh",
		importUrl: "https://raw.githubusercontent.com/veeceoh/xiaomi-hubitat/master/devicedrivers/xiaomi-smart-socket-hubitat.src/xiaomi-smart-socket-hubitat.groovy") {
	capability "Actuator"
        capability "PowerMeter"

	attribute "actuatorState", "enum", ['off', 'on']
        attribute "lastCheckinEpoch", "String"
	attribute "lastCheckinTime", "String"

        // Xiaomi Smart Socket GMR4004CN
        // profileId: 0x0104 Home Automation (HA) profile
        // inClusters: Basic 0x0000, Power configuration 0x0001, Device Temperature Configuration 0x0002, Identify 0x0003, Groups 0x0004, Scenes 0x0005, OnOff 0x0006, Time 0x000A, Binary output 0x0010
        // outClusters: Time 0x000A, OTA upgrade 0x0019
        fingerprint profileId: "0104", inClusters: "0000,0001,0002,0003,0004,0005,0006,000A,0010", outClusters: "000A,0019", manufacturer: "LUMI", model: "lumi.plug"
	
        command "identify"
	command "off"
        command "on"
        command "toggle"
    }
    
    preferences {
	//Logging Message Config
	input name: "infoLogging", type: "bool", title: "Enable info message logging", description: ""
	input name: "debugLogging", type: "bool", title: "Enable debug message logging", description: ""
    }
}


// Parse incoming device messages to generate events
def parse(String description) {
    displayDebugLog("Raw message data: ${description}")
    if (description.startsWith("catchall")) return // TODO: what is catchall?
    
    def descMap = zigbee.parseDescriptionAsMap(description)
    Map eventMap = [:]
    
    displayDebugLog("Message: ${descMap}")
    
    // lastCheckinEpoch is for apps that can use Epoch time/date and lastCheckinTime can be used with Hubitat Dashboard
    sendEvent(name: "lastCheckinEpoch", value: now())
    sendEvent(name: "lastCheckinTime", value: new Date().toLocaleString())
    
    switch (descMap.clusterInt) {
        case 0x0006: // OnOff
            // TODO: semantics of whole additional attribute string
            def actuatorState = (Integer.parseInt(descMap.value, 16) == 0 ? 'off' : 'on')
            // hub/button heuristics is observed behavior, not confirmed from any specs
            def deviceButtonPressed = ((Integer.parseInt(descMap.additionalAttrs[0].value[0..1]).byteValue() & (1 << 2)) != 0 ? " remotely via hub" : " manually using button on device")
            eventMap = [ name: 'actuatorState',
                        value: actuatorState,
                        isStateChange: true,
                        descriptionText: "Actuator state changed to ${actuatorState}${deviceButtonPressed}"
            ]
            break
        case 0x000c: // Analog Input (Basic) Server
            switch (descMap.attrId) {
                case "0055": // PresentValue (single precision)
                    def presentValue = Float.intBitsToFloat(Long.parseLong(descMap.value, 16).intValue())
                    eventMap = [ name: 'power',
                                value: presentValue,
                                isStateChange: true,
                                descriptionText: "Power meter: ${presentValue}"
                    ]
                    break
                default:
                    displayDebugLog("Unsupported attribute ${descMap.attrId} in ${descMap}")
            }
            break
        default:
            displayDebugLog("Unsupported cluster ${descMap.clusterInt} for ${descMap}")
            break
    }
    
    if (eventMap != [:]) {
        displayDebugLog("Creating event ${eventMap}")
	return createEvent(eventMap)
    } else
	return [:]
}


private def displayDebugLog(message) {
    if (debugLogging) log.debug "${device.displayName}: ${message}"
}

private def displayInfoLog(message) {
    if (infoLogging || state.prefsSetCount != 1)
	log.info "${device.displayName}: ${message}"
}


// Switch relay off
def off(paired) {
    def newlyPaired = paired ? " for newly paired sensor" : ""
    displayInfoLog("Set actuator off${newlyPaired}")
    zigbee.command(0x0006, 0x00) // 0x0006 OnOff cluster, 0x00 off
}

// Switch relay on
def on(paired) {
    def newlyPaired = paired ? " for newly paired sensor" : ""
    displayInfoLog("Set actuator on${newlyPaired}")
    zigbee.command(0x0006, 0x01) // 0x0006 OnOff cluster, 0x01 on
}

// Toggle relay
def toggle(paired) {
    def newlyPaired = paired ? " for newly paired sensor" : ""
    displayInfoLog("Toggle actuator${newlyPaired}")
    zigbee.command(0x0006, 0x02) // 0x0006 OnOff cluster, 0x02 toggle
}


def identify() {
    displayInfoLog("Identify actuator")
    return zigbee.command(0x0003, 0x00, "0100") // 0100 is an arbitrary choice for GMR4004CN, it seems not to control time spent in identify mode
}


// installed() runs just after a sensor is paired
def installed() {
    state.prefsSetCount = 0
    displayInfoLog("Installing")
}

// configure() runs after installed() when a sensor is paired or reconnected
def configure() {
    displayInfoLog("Configuring")
    init()
    state.prefsSetCount = 1
    return
}

// updated() runs every time user saves preferences
def updated() {
    displayInfoLog("Updating preference settings")
    init()
    displayInfoLog("Info message logging enabled")
    displayDebugLog("Debug message logging enabled")
}

def init() {
    def modelText = "GMR4004CN"
    def zigbeeModel = device.data.model
    displayInfoLog("Reported ZigBee model ID is $zigbeeModel")
    displayInfoLog("Reported device model is $modelText.")
}
