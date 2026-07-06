#pragma once

#include "protocolCraft/BasePacket.hpp"

#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
#include "protocolCraft/Types/ClockNetworkState.hpp"

#include <map>
#endif

namespace ProtocolCraft
{
    class ClientboundSetTimePacket : public BasePacket<ClientboundSetTimePacket>
    {
    public:
        static constexpr std::string_view packet_name = "Set Time";

        SERIALIZED_FIELD(GameTime, long long int);
#if PROTOCOL_VERSION < 775 /* < 26.1 */
        SERIALIZED_FIELD(DayTime, long long int);
#if PROTOCOL_VERSION > 767 /* > 1.21.1 */
        SERIALIZED_FIELD(TickDayTime, bool);
#endif
#else
        SERIALIZED_FIELD(ClockUpdates, std::map<VarInt, ClockNetworkState>);
#endif

        DECLARE_READ_WRITE_SERIALIZE;
    };
} //ProtocolCraft
