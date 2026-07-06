#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
#pragma once

#include "protocolCraft/NetworkType.hpp"
#include "protocolCraft/Types/Identifier.hpp"

#include <string>

namespace ProtocolCraft
{
    class SetGameRuleEntry : public NetworkType
    {
        SERIALIZED_FIELD(GameRuleKey, Identifier);
        SERIALIZED_FIELD(Value, std::string);

        DECLARE_READ_WRITE_SERIALIZE;
    };
}
#endif
