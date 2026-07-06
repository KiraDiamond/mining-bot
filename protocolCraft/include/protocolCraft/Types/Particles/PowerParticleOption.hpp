#if PROTOCOL_VERSION > 772 /* > 1.21.8 */
#pragma once

#include "protocolCraft/Types/Particles/ParticleOption.hpp"

namespace ProtocolCraft
{
    class PowerParticleOption : public ParticleOption
    {
        SERIALIZED_FIELD(Power, float);

        DECLARE_READ_WRITE_SERIALIZE;
    };
}
#endif
