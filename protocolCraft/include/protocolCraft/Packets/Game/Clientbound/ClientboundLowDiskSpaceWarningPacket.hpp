#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
#pragma once

#include "protocolCraft/BasePacket.hpp"

namespace ProtocolCraft
{
    class ClientboundLowDiskSpaceWarningPacket : public BasePacket<ClientboundLowDiskSpaceWarningPacket>
    {
    public:
        static constexpr std::string_view packet_name = "Low Disk Space Warning";

        DECLARE_READ_WRITE_SERIALIZE;
    };
} //ProtocolCraft
#endif
