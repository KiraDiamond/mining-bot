#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
#pragma once

#include "protocolCraft/BasePacket.hpp"

#include "protocolCraft/Types/Identifier.hpp"

#include <map>

namespace ProtocolCraft
{
    class ClientboundGameRuleValuesPacket : public BasePacket<ClientboundGameRuleValuesPacket>
    {
    public:
        static constexpr std::string_view packet_name = "Game Rule Values";

        SERIALIZED_FIELD(Values, std::map<Identifier, std::string>);

        DECLARE_READ_WRITE_SERIALIZE;
    };
} //ProtocolCraft
#endif
