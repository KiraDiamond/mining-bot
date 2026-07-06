#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
#pragma once

#include "protocolCraft/BasePacket.hpp"

namespace ProtocolCraft
{
    class ServerboundAttackPacket : public BasePacket<ServerboundAttackPacket>
    {
    public:
        static constexpr std::string_view packet_name = "Attack";

        SERIALIZED_FIELD(EntityId, VarInt);

        DECLARE_READ_WRITE_SERIALIZE;
    };
} //ProtocolCraft
#endif
