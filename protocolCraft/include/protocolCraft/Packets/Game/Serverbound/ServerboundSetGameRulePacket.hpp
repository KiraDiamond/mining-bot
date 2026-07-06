#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
#pragma once

#include "protocolCraft/BasePacket.hpp"
#include "protocolCraft/Types/SetGameRuleEntry.hpp"

namespace ProtocolCraft
{
    class ServerboundSetGameRulePacket : public BasePacket<ServerboundSetGameRulePacket>
    {
    public:
        static constexpr std::string_view packet_name = "Set Game Rule";

        SERIALIZED_FIELD(Entries, std::vector<SetGameRuleEntry>);

        DECLARE_READ_WRITE_SERIALIZE;
    };
} //ProtocolCraft
#endif
