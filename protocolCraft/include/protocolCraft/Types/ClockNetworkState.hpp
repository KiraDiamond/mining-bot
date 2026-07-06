#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
#pragma once

#include "protocolCraft/NetworkType.hpp"
#include "protocolCraft/Types/Identifier.hpp"

#include <string>

namespace ProtocolCraft
{
    class ClockNetworkState : public NetworkType
    {
        SERIALIZED_FIELD(TotalTicks, VarLong);
        SERIALIZED_FIELD(PartialTick, float);
        SERIALIZED_FIELD(Rate, float);

        DECLARE_READ_WRITE_SERIALIZE;
    };
}
#endif
