/*
 *
 *  Tuya Zigbee Contact Sensor Version v1.0.0
 *
 *  ver. 1.0.0  2023-02-06 amangalore  - Inital version
 *
 */
def version() { "1.0.0" }
def timeStamp() {"2023/02/06 04:20 PM"}

import groovy.json.*
import groovy.transform.Field
import hubitat.zigbee.zcl.DataType
import hubitat.device.HubAction
import hubitat.device.Protocol
import hubitat.zigbee.clusters.iaszone.ZoneStatus

metadata {
	definition (name: "Tuya Zigbee Contact Sensor", namespace: "Abhay", author: "Abhay Mangalore", importUrl: "to-do") {
        capability "Sensor"
		capability "Refresh"
        capability "ContactSensor"
		capability "IlluminanceMeasurement"
		capability "Battery"
		
        command "resetToOpen"
        command "resetToClosed"
        command "configure", [[name: "Configure the sensor after switching drivers"]]
        command "initialize", [[name: "Initialize the sensor after switching drivers.  \n\r   ***** Will load device default values! *****" ]]
        command "refresh",   [[name: "May work if sensor is awake"]]		

        fingerprint deviceJoinName: "Tuya Contact Sensor", model: "TS0601", profileId: "0104", endpointId:"01", deviceId: "EF00", inClusters:"0001,0500,0000", outClusters:"0019,000A", manufacturer: "_TZE200_pay2byax"
  }

  preferences {
      input(name: "debugLogging", type: "bool", title: "Enable debug logging", description: "", defaultValue: false, submitOnChange: true, displayDuringSetup: false, required: true)
      input(name: "infoLogging", type: "bool", title: "Enable info logging", description: "", defaultValue: true, submitOnChange: true, displayDuringSetup: false, required: true)
	}

}
private getCLUSTER_TUYA()       { 0xEF00 }
private getSETDATA()            { 0x00 }
private getSETTIME()            { 0x24 }

// Tuya Commands
private getTUYA_REQUEST()       { 0x00 }
private getTUYA_REPORTING()     { 0x01 }
private getTUYA_QUERY()         { 0x02 }
private getTUYA_STATUS_SEARCH() { 0x06 }

// Tuya DP type
private getDP_TYPE_RAW()        { "01" }    // [ bytes ]
private getDP_TYPE_BOOL()       { "01" }    // [ 0/1 ]
private getDP_TYPE_VALUE()      { "02" }    // [ 4 byte value ]
private getDP_TYPE_STRING()     { "03" }    // [ N byte string ]
private getDP_TYPE_ENUM()       { "04" }    // [ 0-255 ]
private getDP_TYPE_BITMAP()     { "05" }    // [ 1,2,4 bytes ] as bits

// called on initial install of device during discovery
// also called from initialize() in this driver!
def installed() {
    logInfo("${device.displayName} installed()")
    unschedule()
}

def refresh() {
    ArrayList<String> cmds = []
    cmds += zigbee.readAttribute(0x0000, [0x0007, 0xfffe], [:], delay=200)     // Power Source, attributeReportingStatus
    logDebug("${device.displayName} refresh()...")
    sendZigbeeCommands( cmds ) 
}

def tuyaBlackMagic() {
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0000, [0x0004, 0x000, 0x0001, 0x0005, 0x0007, 0xfffe], [:], delay=200)    // Cluster: Basic, attributes: Man.name, ZLC ver, App ver, Model Id, Power Source, attributeReportingStatus
    cmds += zigbee.writeAttribute(0x0000, 0xffde, 0x20, 0x13, [:], delay=200)
    return  cmds
}

// called when used with capability "Configuration" is called when the configure button is pressed on the device page. 
// Runs when driver is installed, after installed() is run. if capability Configuration exists, a Configure command is added to the ui
// It is also called on initial install after discovery.
def configure() {
    logInfo("${device.displayName} configure()..")
 
    ArrayList<String> cmds = []
    cmds += tuyaBlackMagic()    
    sendZigbeeCommands(cmds)    
}

// called when used with capability "Initialize" it will call this method every time the hub boots up. So for things that need refreshing or re-connecting (LAN integrations come to mind here) ..
// runs first time driver loads, ie system startup 
// when capability Initialize exists, a Initialize command is added to the ui.
def initialize( ) {
    logInfo("${device.displayName} Initialize")
    unschedule()
	
    installed()
    configure()
}

void sendZigbeeCommands(ArrayList<String> cmd) {
    logDebug("${device.displayName} <b>sendZigbeeCommands</b> (cmd=$cmd)")
    hubitat.device.HubMultiAction allActions = new hubitat.device.HubMultiAction()
    cmd.each {
            allActions.add(new hubitat.device.HubAction(it, hubitat.device.Protocol.ZIGBEE))
            if (state.txCounter != null) state.txCounter = state.txCounter + 1
    }
    sendHubCommand(allActions)
}

def void parse(String description) {
	logDebug("${device.displayName}")
    logDebug("Parsing: '${description}'")
	
	if (description?.startsWith('catchall:') || description?.startsWith('read attr -')) {
	    Map descMap  = zigbee.parseDescriptionAsMap(description)
        logInfo("descMap: ${descMap }")
		
		if (descMap.clusterInt == 0x0001 && descMap.commandInt != 0x07 && descMap?.value) {
            if (descMap.attrInt == 0x0021) {
                getBatteryPercentageResult(Integer.parseInt(descMap.value,16))
            } else if (descMap.attrInt == 0x0020){
                getBatteryResult(Integer.parseInt(descMap.value, 16))
            }
            else {
                if (settings?.logEnable) log.warn "${device.displayName} power cluster not parsed attrint $descMap.attrInt"
            }
        }
		else if (descMap.cluster == "0400" && descMap.attrId == "0000") {
            def rawLux = Integer.parseInt(descMap.value,16)
            illuminanceEvent( rawLux )
		}  
		else if (descMap?.clusterInt == CLUSTER_TUYA) {
            processTuyaCluster( descMap )
        }
		else if (descMap.profileId == "0000") {    // zdo
            parseZDOcommand(descMap)
        } 
        else if (descMap?.cluster == "0000" && descMap?.attrId == "0001") {
            logInfo("${device.displayName} Tuya check-in (application version is ${descMap?.value})")
        } 
        else if (descMap?.cluster == "0000" && descMap?.attrId == "0004") {
            logInfo("${device.displayName} Tuya device manufacturer is ${descMap?.value}")
        }
		else if (descMap?.cluster == "0000" && descMap?.attrId == "FFDF") {
            logInfo("${device.displayName} Tuya check-in")
        } 
        else if (descMap?.cluster == "0000" && descMap?.attrId == "FFE2") {
            logInfo("${device.displayName} Tuya AppVersion is ${descMap?.value}")
        } 
        else if (descMap?.cluster == "0000" && descMap?.attrId == "FFE4") {
            logInfo("${device.displayName} Tuya UNKNOWN attribute FFE4 value is ${descMap?.value}")
        } 
        else if (descMap?.cluster == "0000" && descMap?.attrId == "FFFE") {
            logInfo("${device.displayName} Tuya UNKNOWN attribute FFFE value is ${descMap?.value}")
        }
		else if (descMap?.command == "04") {    //write attribute response (other)
            logInfo("${device.displayName} write attribute response is ${descMap?.data[0] == "00" ? "success" : "<b>FAILURE</b>"}")
        } 
        else if (descMap?.command == "00" && descMap?.clusterId == "8021" ) {    // bind response
            logInfo("${device.displayName }bind response, data=${descMap.data} (Sequence Number:${descMap.data[0]}, Status: ${descMap.data[1]=="00" ? 'Success' : '<b>FAILURE</b>'})")
        } 
        else {
            logInfo("${device.displayName} <b> NOT PARSED </b> : descMap = ${descMap}")
        }
	}
	else {
	    log.warn "${device.displayName} <b> UNPROCESSED </b> description = ${description} descMap = ${zigbee.parseDescriptionAsMap(description)}"
	}
}

def parseZDOcommand( Map descMap ) {
    switch (descMap.clusterId) {
        case "0006" :
            logInfo("${device.displayName} Received match descriptor request, data=${descMap.data} (Sequence Number:${descMap.data[0]}, Input cluster count:${descMap.data[5]} Input cluster: 0x${descMap.data[7]+descMap.data[6]})")
            break
        case "0013" : // device announcement
            logInfo("${device.displayName} Received device announcement, data=${descMap.data} (Sequence Number:${descMap.data[0]}, Device network ID: ${descMap.data[2]+descMap.data[1]}, Capability Information: ${descMap.data[11]})")
            break
        case "8004" : // simple descriptor response
            logInfo("${device.displayName} Received simple descriptor response, data=${descMap.data} (Sequence Number:${descMap.data[0]}, status:${descMap.data[1]}, lenght:${hubitat.helper.HexUtils.hexStringToInt(descMap.data[4])}")
            break
        case "8005" : // endpoint response
            def endpointCount = descMap.data[4]
            def endpointList = descMap.data[5]
            logInfo("${device.displayName} zdo command: cluster: ${descMap.clusterId} (endpoint response) endpointCount = ${endpointCount}  endpointList = ${endpointList}")
            break
        case "8021" : // bind response
            logInfo("${device.displayName} Received bind response, data=${descMap.data} (Sequence Number:${descMap.data[0]}, Status: ${descMap.data[1]=="00" ? 'Success' : '<b>Failure</b>'})")
            break
        case "8022" : //unbind request
            logInfo("${device.displayName} zdo command: cluster: ${descMap.clusterId} (unbind request)")
            break
        case "8034" : //leave response
            logInfo("${device.displayName} zdo command: cluster: ${descMap.clusterId} (leave response)")
            break
        default :
            log.warn "${device.displayName} Unprocessed ZDO command: cluster=${descMap.clusterId} command=${descMap.command} attrId=${descMap.attrId} value=${descMap.value} data=${descMap.data}"
    }
}

def processTuyaCluster( descMap ) {
    if (descMap?.clusterInt==CLUSTER_TUYA && descMap?.command == "0B") {    // ZCL Command Default Response
        String clusterCmd = descMap?.data[0]
        def status = descMap?.data[1]            
        logDebug("${device.displayName} device has received Tuya cluster ZCL command 0x${clusterCmd} response 0x${status} data = ${descMap?.data}")
        if (status != "00") {
            log.warn "${device.displayName} ATTENTION! manufacturer = ${device.getDataValue("manufacturer")} unsupported Tuya cluster ZCL command 0x${clusterCmd} response 0x${status} data = ${descMap?.data} !!!"                
        }
    } 
    else if ((descMap?.clusterInt==CLUSTER_TUYA) && (descMap?.command == "01" || descMap?.command == "02"|| descMap?.command == "06"))
    {
        def transid = zigbee.convertHexToInt(descMap?.data[1])           // "transid" is just a "counter", a response will have the same transid as the command
        def dp = zigbee.convertHexToInt(descMap?.data[2])                // "dp" field describes the action/message of a command frame
        def dp_id = zigbee.convertHexToInt(descMap?.data[3])             // "dp_identifier" is device dependant
        def fncmd = getTuyaAttributeValue(descMap?.data)                 // 
        logDebug("${device.displayName}  dp_id=${dp_id} dp=${dp} fncmd=${fncmd}")
        switch (dp) {
            case 0x01 :
                logDebug("${device.displayName} (DP=0x01) motion event fncmd = ${fncmd}")
                handleContact(contactPos = fncmd)
                break
            case 0x02 :
                logDebug("Tuya battery status report dp_id=${dp_id} dp=${dp} fncmd=${fncmd}")
                handleTuyaBatteryLevel( fncmd )                    
                break				
            case 0x04 :
                logDebug("Tuya battery status report dp_id=${dp_id} dp=${dp} fncmd=${fncmd}")
                handleTuyaBatteryLevel( fncmd )                    
                break
            case 0x0C : // (12)
                illuminanceEventLux( fncmd )
                break
            case 0x65 :    // (101)
                illuminanceEventLux(fncmd) // illuminance for TS0601 ContactSensor with LUX
                break            
            case 0x66 :     // (102)
                logDebug("${device.displayName} Tuya battery status report dp_id=${dp_id} dp=${dp} fncmd=${fncmd}")
                handleTuyaBatteryLevel( fncmd )                    
                break
            case 0x93 : // (147)
            case 0xA8 : // (168)
            case 0xA4 : // (164)
            case 0x8C : // (140)
            case 0x7A : // (122)
            case 0xAD : // (173)
            case 0xAE : // (174)
            case 0xAA : // (170)
                logDebug("${device.displayName} reported unknown parameter dp=${dp} value=${fncmd}")
                break
            default :
                log.warn "${device.displayName} <b>NOT PROCESSED</b> Tuya cmd: dp=${dp} value=${fncmd} descMap.data = ${descMap?.data}"
                break
        }
    } // Tuya commands '01' and '02'
    else {
        log.warn "${device.displayName} <b>NOT PROCESSED</b> Tuya <b>descMap?.command = ${descMap?.command}</b> cmd: dp=${dp} value=${fncmd} descMap.data = ${descMap?.data}" 
    }
}

private int getTuyaAttributeValue(ArrayList _data) {
    int retValue = 0
    
    if (_data.size() >= 6) {
        int dataLength = _data[5] as Integer
        int power = 1;
        for (i in dataLength..1) {
            retValue = retValue + power * zigbee.convertHexToInt(_data[i+5])
            power = power * 256
        }
    }
    return retValue
}

private handleContact( contactPos, isDigital=false ) {
    def map = [:]
    map.name = "contact"
    map.value = contactPos ? "open" : "closed"    // open or closed
    map.type = isDigital == true ? "digital" : "physical"
    map.descriptionText = "${device.displayName} contact is ${map.value}"
    if (device.currentValue("contact", true) != null && device.currentState('contact').value == (contactPos ? "open" : "closed")) {
	   logDebug("${device.displayName} ignored repeated event")
	}
	else {
	   logDebug("${device.displayName} ${map.descriptionText}")
	   sendEvent(map)
	}
}

def illuminanceEvent( rawLux ) {
	def lux = rawLux > 0 ? Math.round(Math.pow(10,(rawLux/10000))) : 0
    illuminanceEventLux( lux as Integer) 
}

def illuminanceEventLux( lux ) {
    if (device.currentValue("illuminance", true) == null ||  Math.abs(safeToInt(device.currentValue("illuminance")) - (lux as int)) >= settings?.luxThreshold) {
        sendEvent("name": "illuminance", "value": lux, "unit": "lx", "type": "physical", "descriptionText": "Illuminance is ${lux} Lux")
        logInfo("$device.displayName Illuminance is ${lux} Lux")
    }
}

def handleTuyaBatteryLevel( fncmd ) {
    def rawValue = 0
    if (fncmd == 0) rawValue = 100           // Battery Full
    else if (fncmd == 1) rawValue = 75       // Battery High
    else if (fncmd == 2) rawValue = 50       // Battery Medium
    else if (fncmd == 3) rawValue = 25       // Battery Low
    else if (fncmd == 4) rawValue = 100      // Tuya 3 in 1 -> USB powered
    else rawValue = fncmd
    getBatteryPercentageResult(rawValue*2)
}

def getBatteryPercentageResult(rawValue) {
    logDebug("${device.displayName} Battery Percentage rawValue = ${rawValue} -> ${rawValue / 2}%")
    def result = [:]

    if (0 <= rawValue && rawValue <= 200) {
        result.name = 'battery'
        result.translatable = true
        result.value = Math.round(rawValue / 2)
        result.descriptionText = "${device.displayName} battery is ${result.value}%"
        result.isStateChange = true
        result.unit  = '%'
        sendEvent(result)
        state.lastBattery = result.value
        logInfo("${result.descriptionText}")
    }
    else {
        log.warn "${device.displayName} ignoring BatteryPercentageResult(${rawValue})"
    }
}

def getBatteryResult(rawValue) {
    logDebug("${device.displayName} batteryVoltage = ${(double)rawValue / 10.0} V")
    def result = [:]
    def volts = rawValue / 10
    if (!(rawValue == 0 || rawValue == 255)) {
        def minVolts = 2.1
        def maxVolts = 3.0
        def pct = (volts - minVolts) / (maxVolts - minVolts)
        def roundedPct = Math.round(pct * 100)
        if (roundedPct <= 0) roundedPct = 1
        result.value = Math.min(100, roundedPct)
        result.descriptionText = "${device.displayName} battery is ${result.value}% (${volts} V)"
        result.name = 'battery'
        result.unit  = '%'
        result.isStateChange = true
        logInfo("${result.descriptionText}")
        sendEvent(result)
        state.lastBattery = result.value
    }
    else {
        log.warn "${device.displayName} ignoring BatteryResult(${rawValue})"
    }    
}

def sendBatteryEvent( roundedPct, isDigital=false ) {
    if (roundedPct > 100) roundedPct = 100
    if (roundedPct < 0)   roundedPct = 0
    sendEvent(name: 'battery', value: roundedPct, unit: "%", type:  isDigital == true ? "digital" : "physical", isStateChange: true )    
}

Integer safeToInt(val, Integer defaultVal=0) {
	return "${val}"?.isInteger() ? "${val}".toInteger() : defaultVal
}

Double safeToDouble(val, Double defaultVal=0.0) {
	return "${val}"?.isDouble() ? "${val}".toDouble() : defaultVal
}

private void logDebug(message) {
    if (debugLogging) log.debug(message)
}

private void logInfo(message) {
    if (infoLogging) log.info(message)
}

void resetToOpen() {
    logInfo("resetToOpen()")
    handleContact(1)
}

void resetToClosed() {
    logInfo("resetToClosed()")
    handleContact(0)
}